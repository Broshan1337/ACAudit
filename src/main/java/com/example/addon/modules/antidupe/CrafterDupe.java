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
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Crafter Slot-Toggle Race (1.21 Crafter block)
 *
 * The Crafter block (1.21) crafts on a redstone pulse from its 3x3 grid, and the
 * client can toggle individual grid slots enabled/disabled with plain GUI clicks.
 * This module floods toggle clicks across the 3×3 grid (slots 0–8) AND
 * QUICK_MOVE clicks on the output slot (slot 9), so both toggle edits and
 * output-grab attempts interleave with the redstone-driven craft step.
 *
 * Run it with the Crafter wired to a fast redstone clock (and items loaded).
 * If the craft operation reads the grid/toggle mask without locking against
 * concurrent toggle edits, you can craft from a slot mid-toggle and get an
 * output that consumes nothing - or consumes the same input twice.
 *
 * Patch signal: snapshot the grid + enabled-slot mask under a lock at the start
 * of each craft tick and validate ingredient consumption against that snapshot
 * atomically; reject (or queue) toggle edits that arrive during a craft.
 */
public class CrafterDupe extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> togglesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("toggles-per-tick").description("Slot-toggle clicks sent across the 3×3 grid each tick.")
        .defaultValue(18).range(1, 200).sliderRange(1, 50).build()
    );
    private final Setting<Integer> outputGrabsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("output-grabs-per-tick")
        .description("QUICK_MOVE clicks on the output slot (slot 9) each tick. Tests output-grab vs. craft-step atomicity. Set 0 to disable.")
        .defaultValue(5).range(0, 50).sliderRange(0, 20).build()
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

    public CrafterDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "crafter-dupe",
            "Floods Crafter grid slot-toggle clicks while it crafts. Tests craft-vs-toggle atomicity (run with a redstone clock).");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        obs.tick();
        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        // Toggle grid slots 0–8
        for (int i = 0; i < togglesPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short)(i % 9), (byte) 0, SlotActionType.PICKUP,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
        // Grab from output slot 9 — races the craft step's output commit
        for (int i = 0; i < outputGrabsPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) 9, (byte) 0, SlotActionType.QUICK_MOVE,
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
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.onReceive(event.packet);
        if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket p)
            info("Server updated slot %d → %s (syncId %d)", p.getSlot(), p.getStack().getName().getString(), p.getSyncId());
        else if (event.packet instanceof InventoryS2CPacket p)
            info("Server resynced inventory (syncId %d, %d slots)", p.syncId(), p.contents().size());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
