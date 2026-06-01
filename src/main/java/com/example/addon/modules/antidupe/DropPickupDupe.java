package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Drop + Pickup Dupe (stale-revision THROW / PICKUP race)
 *
 * Sends THROW then PICKUP on the same slot with revision = 0 (stale), both
 * in the same tick. A server that does not enforce revision validation on
 * THROW can drop the item while the slot still appears full; the PICKUP in
 * the same tick then observes the full slot and picks it back up — so the
 * item both lands on the ground AND remains in the inventory.
 *
 * This is among the oldest and simplest inventory duplication vectors: no
 * open container, no elevated permissions, just two crafted click packets on
 * the player's own inventory.
 *
 * Patch signal: enforce that every ClickSlotC2SPacket's revision matches the
 * server's current state sequence; on mismatch, reject and send a resync.
 * THROW must be atomic with the resulting dropped-entity creation — remove
 * the item from inventory before the entity is spawned, never concurrently.
 */
public class DropPickupDupe extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> targetSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Inventory slot to drop+pickup.")
        .defaultValue(0).range(0, 44).sliderRange(0, 44).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public DropPickupDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "drop-pickup-dupe",
            "THROW+PICKUP on same slot with revision=0. Tests stale-revision rejection.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        int  syncId = mc.player.currentScreenHandler.syncId;
        short slot  = (short) (int) targetSlot.get();
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, 0, slot, (byte) 0, SlotActionType.THROW,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, 0, slot, (byte) 0, SlotActionType.PICKUP,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
