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
 * AUDIT: Death Inventory Race
 *
 * When the player's health drops below a threshold, fires rapid inventory
 * operations (QUICK_MOVE, THROW, offhand-swap) on every tick. The bet: the
 * server processes death (loot-table drop generation, inventory clear, respawn)
 * at the same tick a client-initiated inventory operation arrives — if death
 * handling and inventory mutation aren't serialised under one lock, an item that
 * should be dropped can simultaneously be moved somewhere else by the race.
 *
 * Also surfaces whether "keep-inventory" (enchant/plugin) is applied before or
 * after the race window closes.
 *
 * Patch signal: death processing must hold the player's inventory lock for its
 * full duration — drop generation + clear must be atomic with respect to any
 * concurrently arriving inventory packet. Inventory packets received during death
 * processing should be queued or rejected.
 *
 * Set health-threshold to near your current HP, then take damage.
 */
public class DeathInventoryRace extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> healthThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("health-threshold").description("Start firing when HP falls at or below this.")
        .defaultValue(4.0).range(0.5, 20.0).sliderRange(0.5, 10.0).build()
    );
    private final Setting<Integer> opsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("ops-per-tick").description("Inventory operations per tick when triggered.")
        .defaultValue(10).range(1, 200).sliderRange(1, 50).build()
    );
    private final Setting<Boolean> throwItems = sgGeneral.add(new BoolSetting.Builder()
        .name("throw-items").description("Send THROW clicks to drop items from hotbar.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> swapOffhand = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-offhand").description("Send offhand-swap packets during the race.")
        .defaultValue(true).build()
    );

    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public DeathInventoryRace() {
        super(AddonTemplate.DUPE_CATEGORY, "death-inventory-race",
            "Floods inventory ops when health is critically low. Tests death-processing vs. inventory-mutation atomicity.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        if (mc.player.getHealth() > healthThreshold.get().floatValue()) return;

        var handler = mc.player.currentScreenHandler;
        if (handler == null) return;
        int syncId = handler.syncId;
        int rev = handler.getRevision();

        for (int i = 0; i < opsPerTick.get(); i++) {
            int slot = i % 9; // cycle hotbar
            if (throwItems.get()) {
                // THROW the item in this hotbar slot
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) (36 + slot), (byte) 1, SlotActionType.THROW,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
            }
            if (swapOffhand.get()) {
                mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            packetsSent++;
            }
            // Also quick-move from hotbar to container (or player inv if no container open)
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) (36 + slot), (byte) 0, SlotActionType.QUICK_MOVE,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
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
