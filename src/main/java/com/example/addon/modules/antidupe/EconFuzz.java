package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Economy Value Fuzzer
 *
 * Cycles a configurable command template through edge-case numeric strings,
 * one per send. Targets the number-parsing layer of economy plugins (/sell,
 * /ah sell, /pay, etc.) that pass raw user input into Integer.parseInt /
 * Double.parseDouble / BigDecimal or a SQL amount column without validation.
 *
 * Patch signal: parse inside try/catch, reject non-finite / negative / out-of
 * range values BEFORE touching balances, and use a fixed-precision money type
 * (BigDecimal/long cents) rather than double.
 */
public class EconFuzz extends Module {
    private static final String[] VALUES = {
        // sane baseline
        "0", "1", "64",
        // negatives — should never credit money
        "-1", "-64", "-1000000",
        // decimals where an int may be expected (and precision attacks)
        "0.0001", "1.5", "0.1", "0.999999999999",
        // scientific notation — the classic economy bypass
        "1e6", "1E6", "1e06", "-1e6", "1e-6", "1.5e3",
        "1e308", "1e309",            // 1e309 overflows double -> Infinity
        // integer / long boundaries and overflow
        String.valueOf(Integer.MAX_VALUE),       // 2147483647
        String.valueOf(Integer.MIN_VALUE),       // -2147483648
        "2147483648",                            // int overflow
        String.valueOf(Long.MAX_VALUE),
        String.valueOf(Long.MIN_VALUE),
        "9223372036854775808",                   // long overflow
        "100000000000000000000000000000",        // beyond long
        // special float values
        "NaN", "Infinity", "-Infinity", "+Infinity",
        // formatting / locale tricks
        "+100", " 100 ", "1,000", "1.000.000", "1_000", "0x10", "010", "1f", "1d", "1L",
        // empty / whitespace
        "", " ", "\t",
        // injection probes (prepared statements should neutralise these)
        "1; DROP TABLE players;--", "1' OR '1'='1",
        // very long numeric string
        "9".repeat(500),
    };

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command")
        .description("Command template. {n} is replaced with each fuzz value. No leading slash.")
        .defaultValue("sell {n}")
        .build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Ticks between sends (20 = 1s). Keep this high to avoid the chat-spam kick.")
        .defaultValue(20).range(1, 200).sliderRange(5, 100).build()
    );
    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop")
        .description("Restart the list after the last value instead of disabling.")
        .defaultValue(false).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private int index = 0;
    private int timer = 0;

    public EconFuzz() {
        super(AddonTemplate.DUPE_CATEGORY, "econ-fuzz",
            "Fuzzes an economy command with edge-case numbers (scientific notation, negatives, overflow). Tests value parsing.");
    }

    @Override
    public void onActivate() { index = 0; timer = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (timer > 0) { timer--; return; }

        String value = VALUES[index % VALUES.length];
        info("[%d/%d] %s", (index % VALUES.length) + 1, VALUES.length, command.get().replace("{n}", "\"" + value + "\""));
        mc.player.networkHandler.sendChatCommand(command.get().replace("{n}", value));

        index++;
        timer = delayTicks.get();

        if (index >= VALUES.length) {
            if (loop.get()) index = 0;
            else toggle();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
