package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Bhop (NoJumpDelay + auto-jump)
 *
 * Re-jumps the instant the player lands instead of obeying the vanilla jump
 * cooldown, and force-sprints. Produces a continuous low-hop with no ground
 * settle ticks — tests jump-delay enforcement and on-ground/air state tracking.
 *
 * Subtlety controls:
 *   jitter-velocity  — ±random variation on hop velocity each landing. Tests
 *                      whether the AC catches variance-based bhop or only
 *                      perfectly constant cadence.
 *   skip-probability — chance to skip a landing re-jump. Tests whether the AC
 *                      catches intermittent bhop or only sustained perfect bhop.
 *   settle-ticks     — ticks to wait on ground after landing before re-jumping.
 *                      Tests "jumped-the-same-tick-as-landing" detectors.
 *
 * Combination: Bhop+Timer (timer speeds up tick rate, more hops per second),
 * Bhop+Speed (horizontal boost on each hop), Bhop+OmniSprint (full-BHS combo).
 */
public class Bhop extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> jumpVelocity = sgGeneral.add(new DoubleSetting.Builder()
        .name("jump-velocity").description("Base upward velocity on each hop (vanilla 0.42).")
        .defaultValue(0.42).range(0.1, 1.0).sliderRange(0.1, 0.8).build()
    );
    private final Setting<Double> jitterVelocity = sgGeneral.add(new DoubleSetting.Builder()
        .name("jitter-velocity").description("Random ±velocity variation per hop. Tests variance-based detection.")
        .defaultValue(0.02).range(0.0, 0.1).sliderRange(0.0, 0.08).build()
    );
    private final Setting<Double> skipProbability = sgGeneral.add(new DoubleSetting.Builder()
        .name("skip-probability").description("Chance (0–1) to skip a landing re-jump. Tests intermittent bhop detection.")
        .defaultValue(0.0).range(0.0, 0.5).sliderRange(0.0, 0.3).build()
    );
    private final Setting<Integer> settleTicks = sgGeneral.add(new IntSetting.Builder()
        .name("settle-ticks").description("Ticks to wait after landing before re-jumping (0 = instant).")
        .defaultValue(0).range(0, 3).sliderRange(0, 2).build()
    );
    private final Setting<Boolean> autoSprint = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-sprint").description("Force sprint so each hop carries sprint speed.")
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
    private boolean wasOnGround = false;
    private int settleCountdown = 0;

    public Bhop() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "bhop",
            "Auto-jumps with no landing delay. Tests jump-delay enforcement and ground-state tracking.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; wasOnGround = false; settleCountdown = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        if (autoSprint.get()) mc.player.setSprinting(true);

        boolean onGround = mc.player.isOnGround();

        // Track landing: reset settle countdown on each new ground contact
        if (!wasOnGround && onGround) {
            settleCountdown = settleTicks.get();
        }
        wasOnGround = onGround;

        // Wait out settle ticks before jumping
        if (settleCountdown > 0) {
            settleCountdown--;
            return;
        }

        if (onGround && mc.options.jumpKey.isPressed()) {
            if (Math.random() < skipProbability.get()) return;
            double jit = (Math.random() * 2 - 1) * jitterVelocity.get();
            double vel = Math.max(0.1, jumpVelocity.get() + jit);
            mc.player.setVelocity(mc.player.getVelocity().x, vel, mc.player.getVelocity().z);
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
