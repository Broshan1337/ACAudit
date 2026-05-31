package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

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

    private int index = 0;
    private int timer = 0;

    public SellCommandFuzz() {
        super(AddonTemplate.CRASH_CATEGORY, "sell-command-fuzz",
            "Cycles edge-case quantities through /sell. Tests integer parsing and injection hardening.");
    }

    @Override
    public void onActivate() {
        index = 0;
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (timer > 0) { timer--; return; }
        mc.player.networkHandler.sendChatCommand("sell " + FUZZ_VALUES[index % FUZZ_VALUES.length]);
        index++;
        timer = delayTicks.get();
        if (index >= FUZZ_VALUES.length) toggle();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
