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
 * The player inventory handler (InventoryMenu) slot map is:
 *   0      crafting result      1–4    2×2 crafting grid
 *   5–8    armor                9–35   main inventory
 *   36–44  hotbar               45     offhand
 * Different slot types route through different server-side equip/transfer
 * paths, so a dupe guard that covers the hotbar may still miss armor or
 * offhand. CYCLE_SLOTS mode rotates through one of each type automatically.
 *
 * Patch signal: enforce that every ClickSlotC2SPacket's revision matches the
 * server's current state sequence; on mismatch, reject and send a resync.
 * THROW must be atomic with the resulting dropped-entity creation — remove
 * the item from inventory before the entity is spawned, never concurrently —
 * and this must hold uniformly across armor, offhand, and hotbar slots.
 */
public class DropPickupDupe extends Module {
    public enum Mode { FIXED, CYCLE_SLOTS }

    // One representative slot of each distinct equip/transfer path.
    private static final short[] SLOT_TYPES = { 5, 45, 36, 9 }; // armor, offhand, hotbar, main

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("FIXED = the configured slot each tick. CYCLE_SLOTS = rotate armor / offhand / hotbar / main.")
        .defaultValue(Mode.FIXED).build()
    );
    private final Setting<Integer> targetSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Inventory slot to drop+pickup (FIXED mode). 5–8 armor, 36–44 hotbar, 45 offhand.")
        .defaultValue(36).range(0, 45).sliderRange(0, 45)
        .visible(() -> mode.get() == Mode.FIXED).build()
    );
    private final Setting<Boolean> staleRevision = sgGeneral.add(new BoolSetting.Builder()
        .name("stale-revision").description("Send revision=0 (stale). Disable to use the current server revision.")
        .defaultValue(true).build()
    );
    private final Setting<Integer> pickupBurst = sgGeneral.add(new IntSetting.Builder()
        .name("pickup-burst").description("PICKUP packets sent per THROW. Higher values widen the race window against slower servers.")
        .defaultValue(5).range(1, 50).sliderRange(1, 20).build()
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
    private int slotIdx;

    public DropPickupDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "drop-pickup-dupe",
            "THROW+PICKUP on same slot with revision=0. Tests stale-revision rejection across armor/offhand/hotbar slots.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; slotIdx = 0;
        obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        obs.tick();
        int syncId = mc.player.currentScreenHandler.syncId;
        int rev = staleRevision.get() ? 0 : mc.player.currentScreenHandler.getRevision();

        short slot;
        if (mode.get() == Mode.FIXED) {
            slot = (short) (int) targetSlot.get();
        } else {
            slot = SLOT_TYPES[slotIdx % SLOT_TYPES.length];
            slotIdx++;
        }

        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, rev, slot, (byte) 0, SlotActionType.THROW,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
        for (int i = 0; i < pickupBurst.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, slot, (byte) 0, SlotActionType.PICKUP,
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
