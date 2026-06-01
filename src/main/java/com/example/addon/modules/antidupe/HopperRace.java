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
 * AUDIT: Hopper Transfer Race
 *
 * With a hopper GUI open, spams rapid PICKUP and QUICK_MOVE clicks on the hopper
 * slots at the same time the hopper is running its item-transfer tick. Hoppers
 * transfer items every 8 game ticks on the SERVER thread; if a player GUI click
 * on those same slots is processed between the "is there an item here?" check and
 * the "move item to destination" write, a duplication window opens — the item
 * leaves the hopper AND is seen by the player click simultaneously.
 *
 * This is a general container-thread-safety test applied to the hopper, which
 * has its own transfer loop separate from the normal container click path.
 *
 * Patch signal: hopper item-transfer and player container-click for the SAME
 * inventory must be mutually exclusive — either by running both on the same
 * synchronised thread or by holding the inventory lock for the full transfer
 * operation including the destination write.
 *
 * Open a hopper GUI with items inside (and an active destination), enable.
 */
public class HopperRace extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> clicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick").description("Container clicks per tick across hopper slots.")
        .defaultValue(20).range(1, 500).sliderRange(1, 100).build()
    );
    private final Setting<Boolean> quickMove = sgGeneral.add(new BoolSetting.Builder()
        .name("quick-move").description("Use QUICK_MOVE (shift-click) instead of PICKUP.")
        .defaultValue(true).build()
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

    public HopperRace() {
        super(AddonTemplate.DUPE_CATEGORY, "hopper-race",
            "Floods clicks on hopper slots while the hopper transfer tick runs. Tests hopper transfer vs. player-click mutex.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        if (mc.player == null || mc.player.currentScreenHandler == null
            || mc.player.currentScreenHandler == mc.player.playerScreenHandler) {
            if (isActive()) warning("Open a hopper GUI first.");
            return;
        }

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        // Hopper has 5 slots (0-4); cycle through them
        int hopperSlots = Math.min(5, handler.slots.size() - 36);
        SlotActionType action = quickMove.get() ? SlotActionType.QUICK_MOVE : SlotActionType.PICKUP;

        for (int i = 0; i < clicksPerTick.get(); i++) {
            int slot = i % hopperSlots;
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) slot, (byte) 0, action,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
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
