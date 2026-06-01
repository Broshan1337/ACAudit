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
 * AUDIT: Interaction Flood (same-slot click race)
 *
 * With a container open, floods ClickSlotC2SPacket on a single slot each
 * tick using revision = 0 (always stale). This targets the race-condition
 * dupe window created by same-slot click floods: if the server processes each
 * click in sequence without re-checking whether the item is still present, a
 * rapid flood can observe the item N times and award N payouts before the
 * first click's removal is committed.
 *
 * Distinct from AuctionRace (which uses valid revisions on a specific GUI
 * button): this module uses a stale revision to simultaneously probe both
 * the rate-limit surface and the revision-validation surface.
 *
 * Patch signal: rate-limit container clicks per slot per player per tick to
 * at most 1; enforce revision validation before any click is processed; re-
 * verify item presence at commit time, not at request time.
 *
 * Open any container, enable.
 */
public class InteractionFlood extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> clicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick").description("Container click packets per tick.")
        .defaultValue(20).range(1, 200).sliderRange(1, 100).build()
    );
    private final Setting<Integer> targetSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Inventory slot to target.")
        .defaultValue(0).range(0, 45).sliderRange(0, 45).build()
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

    public InteractionFlood() {
        super(AddonTemplate.DUPE_CATEGORY, "interaction-flood",
            "Floods container clicks per tick. Tests race-condition dupe windows and rate limiting.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        int  syncId = mc.player.currentScreenHandler.syncId;
        short slot  = (short) (int) targetSlot.get();
        for (int i = 0; i < clicksPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, 0, slot, (byte) 0,
                SlotActionType.PICKUP, new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
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
