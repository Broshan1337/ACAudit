package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Crafter Slot-Toggle Race (1.21 Crafter block)
 *
 * The Crafter block (1.21) crafts on a redstone pulse from its 3x3 grid, and the
 * client can toggle individual grid slots enabled/disabled with plain GUI clicks.
 * This module floods those toggle clicks across all 9 slots while the Crafter is
 * open, so toggle packets interleave with the redstone-driven craft step.
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
        .name("toggles-per-tick").description("Slot-toggle clicks sent across the 3x3 each tick.")
        .defaultValue(18).range(1, 200).sliderRange(1, 50).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public CrafterDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "crafter-dupe",
            "Floods Crafter grid slot-toggle clicks while it crafts. Tests craft-vs-toggle atomicity (run with a redstone clock).");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        for (int i = 0; i < togglesPerTick.get(); i++) {
            int slot = i % 9; // the Crafter's 3x3 grid slots
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) slot, (byte) 0, SlotActionType.PICKUP,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
