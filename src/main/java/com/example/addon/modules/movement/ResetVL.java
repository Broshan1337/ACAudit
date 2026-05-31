package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: ResetVL
 *
 * Emits a stream of small, deliberately-legal up/down hops on the ground. The
 * goal is to farm "clean" movement ticks. If your AC decays a player's
 * violation level whenever it sees normal-looking movement, a cheater runs
 * this between cheats to flush their VL back to zero and never crosses the
 * ban/kick threshold.
 *
 * DETECTION: do not decay VL on cheaply-produced filler movement. Use a leaky
 * bucket that only drains on movement that passed full physics validation, and
 * keep a separate long-window violation history that filler can't erase.
 */
public class ResetVL extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> hops = sgGeneral.add(new IntSetting.Builder()
        .name("hops")
        .description("Number of clean hops to perform, then disable.")
        .defaultValue(10).range(1, 100).sliderRange(1, 30).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private int done = 0;

    public ResetVL() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "reset-vl",
            "Performs clean filler hops to farm legal ticks. Tests whether your violation-level decay is gameable.");
    }

    @Override
    public void onActivate() { done = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (mc.player.isOnGround()) {
            mc.player.setVelocity(0.0, 0.1, 0.0);
            done++;
            if (done >= hops.get()) toggle();
        } else if (mc.player.getVelocity().y > 0) {
            mc.player.setVelocity(0.0, -0.1, 0.0);
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
