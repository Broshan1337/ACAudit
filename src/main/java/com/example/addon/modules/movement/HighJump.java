package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.JumpVelocityMultiplierEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: HighJump
 *
 * Multiplies the jump take-off velocity (vanilla ~0.42). A multiplier > 1 makes
 * the player leave the ground faster than physics allows. Classic vertical-speed
 * / "jump height" check trigger.
 *
 * Subtlety controls:
 *   multiplier-jitter — ±random variation on the multiplier each jump. Tests
 *                       whether the AC catches "always slightly too high" vs.
 *                       "always exactly Nx too high" (variance vs. threshold).
 *   ramp-mode         — start at 1.0 and increase multiplier by ramp-step each
 *                       jump, finding the AC's exact detection threshold
 *                       dynamically during the session.
 *
 * Combination: HighJump+NoFall (jump high, cancel fall damage). HighJump+AirJump
 * (air-jump again at apex for near-infinite vertical travel).
 *
 * Ported from LiquidBounce HighJump (Vanilla mode).
 */
public class HighJump extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> multiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("multiplier").description("Jump velocity multiplier (1.0 = vanilla). Target / ceiling in ramp mode.")
        .defaultValue(2.0).range(1.0, 10.0).sliderRange(1.0, 5.0).build()
    );
    private final Setting<Double> multiplierJitter = sgGeneral.add(new DoubleSetting.Builder()
        .name("multiplier-jitter").description("Random ±variation on the multiplier each jump. Tests variance-based detection.")
        .defaultValue(0.0).range(0.0, 0.5).sliderRange(0.0, 0.3).build()
    );
    private final Setting<Boolean> rampMode = sgGeneral.add(new BoolSetting.Builder()
        .name("ramp-mode")
        .description("Start at 1.0 and increase by ramp-step each jump. Finds the AC's exact detection threshold.")
        .defaultValue(false).build()
    );
    private final Setting<Double> rampStep = sgGeneral.add(new DoubleSetting.Builder()
        .name("ramp-step").description("Multiplier increment per jump in ramp mode.")
        .defaultValue(0.1).range(0.01, 0.5).sliderRange(0.01, 0.3)
        .visible(rampMode::get).build()
    );

    // ramp-mode + this observer = adaptive threshold-seeking: ramp the multiplier
    // up and read the exact value at which the server starts setting you back.
    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private double currentMultiplier = 1.0;

    public HighJump() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-high-jump",
            "Multiplies jump take-off velocity. Tests vertical-speed / jump-height detection.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; currentMultiplier = 1.0; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) { obs.tick(); }

    @EventHandler
    private void onJumpMultiplier(JumpVelocityMultiplierEvent event) {
        ticksActive++;
        obs.markSent();

        double m;
        if (rampMode.get()) {
            currentMultiplier = Math.min(multiplier.get(), currentMultiplier + rampStep.get());
            m = currentMultiplier;
            info("Ramp jump ×%.2f", m);
        } else {
            m = multiplier.get();
        }

        double jit = (Math.random() * 2 - 1) * multiplierJitter.get();
        event.multiplier = (float) Math.max(1.0, m + jit);
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
