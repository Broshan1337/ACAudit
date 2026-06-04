package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Death Item-Drop Race (axis 7, precise)
 *
 * On death the server generates the drop list, clears the inventory, and (with
 * keep-inventory plugins) may retain some items — a multi-step sequence with a
 * one-tick window. This SNIPEs a single inventory operation (THROW / offhand
 * swap / quick-move) at a precise offset as health crosses the death threshold,
 * to influence which items drop versus which stay. Unlike a flood, one perfectly
 * timed op tests whether drop-generation and inventory-mutation are serialised
 * under one lock.
 *
 * Vulnerable server: processes the racing inventory op against the same inventory
 * the death handler is mid-clearing -> an item both drops AND is moved/kept.
 * Hardened server: holds the inventory lock for the entire death sequence; any
 * inventory packet arriving during it is queued or rejected.
 * Fix: death drop-generation + clear must be atomic w.r.t. concurrent inventory
 * packets; reject inventory mutation while a death is being processed.
 *
 * Set health-threshold near your current HP, then take lethal damage with this on.
 * Run against your OWN server only.
 */
public class DeathDropRace extends Module {
    public enum Op { THROW, OFFHAND_SWAP, QUICK_MOVE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> threshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("health-threshold").description("Arm the snipe when HP falls at or below this.")
        .defaultValue(2.0).range(0.5, 20.0).sliderRange(0.5, 10.0).build()
    );
    private final Setting<Op> op = sgGeneral.add(new EnumSetting.Builder<Op>()
        .name("op").description("Inventory operation to snipe at the death tick.")
        .defaultValue(Op.THROW).build()
    );
    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Hotbar/inventory slot to act on (THROW/QUICK_MOVE).")
        .defaultValue(36).range(0, 45).sliderRange(0, 45).build()
    );

    private final TickWindow window = new TickWindow(sgGeneral);
    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private boolean wasLow = false;

    public DeathDropRace() {
        super(AddonTemplate.DUPE_CATEGORY, "death-drop-race",
            "Snipes one inventory op at the exact death tick. Tests death drop-generation vs. inventory-mutation atomicity.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; wasLow = false;
        window.onActivate(); obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        boolean low = mc.player.getHealth() <= threshold.get().floatValue();
        if (low && !wasLow) window.arm(); // arm on the rising edge into the danger zone
        wasLow = low;

        if (!window.shouldFire()) return;
        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        short s = (short) (int) slot.get();
        switch (op.get()) {
            case THROW -> mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, s, (byte) 1, SlotActionType.THROW, new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            case QUICK_MOVE -> mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, s, (byte) 0, SlotActionType.QUICK_MOVE, new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            case OFFHAND_SWAP -> mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        }
        packetsSent++;
        obs.markFired();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.survey(event.packet, l -> info("%s", l)); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
