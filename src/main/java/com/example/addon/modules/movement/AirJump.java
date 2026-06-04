package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: AirJump
 *
 * Lets the player jump again while airborne. The server sees an upward velocity
 * spike with no ground contact beforehand — a textbook fly/air-movement trigger.
 *
 * Subtlety controls:
 *   jitter        — ±random variation on the applied velocity each jump, testing
 *                   whether the AC catches variance-based cheating or only a fixed
 *                   magnitude threshold.
 *   delay-ticks   — ticks to wait after the jump key is pressed before applying
 *                   velocity, testing "jumped-too-fast-after-ground-contact" detectors.
 *
 * Combination: pair with NoFall (land without damage), AntiSetback (hold
 * altitude after correction), or ResetVL (clean ticks between jumps).
 *
 * Ported from LiquidBounce AirJump (DoubleJump behavior).
 */
public class AirJump extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> maxJumps = sgGeneral.add(new IntSetting.Builder()
        .name("air-jumps").description("Extra jumps allowed before touching ground.")
        .defaultValue(1).range(1, 10).sliderRange(1, 5).build()
    );
    private final Setting<Double> jumpVelocity = sgGeneral.add(new DoubleSetting.Builder()
        .name("jump-velocity").description("Base upward velocity applied per air jump (vanilla 0.42).")
        .defaultValue(0.42).range(0.1, 2.0).sliderRange(0.1, 1.0).build()
    );
    private final Setting<Double> jitter = sgGeneral.add(new DoubleSetting.Builder()
        .name("jitter").description("Random ±velocity variation per air-jump. Tests variance-based detection vs. threshold-only.")
        .defaultValue(0.02).range(0.0, 0.1).sliderRange(0.0, 0.08).build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks after key press before applying velocity. Tests jumped-too-fast detectors.")
        .defaultValue(0).range(0, 5).sliderRange(0, 3).build()
    );

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
    private int used = 0;
    private boolean wasPressed = false;
    private int pendingDelay = 0;

    public AirJump() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-air-jump",
            "Jump again while airborne. Tests fly / air-movement detection.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; used = 0; wasPressed = false; pendingDelay = 0; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        if (mc.player.isOnGround()) {
            used = 0;
            wasPressed = mc.options.jumpKey.isPressed();
            pendingDelay = 0;
            return;
        }

        // Apply delayed jump if countdown is running
        if (pendingDelay > 0) {
            pendingDelay--;
            if (pendingDelay == 0 && mc.player != null && !mc.player.isOnGround()) {
                applyJump();
            }
            wasPressed = mc.options.jumpKey.isPressed();
            return;
        }

        boolean pressed = mc.options.jumpKey.isPressed();
        if (pressed && !wasPressed && used < maxJumps.get()) {
            int delay = delayTicks.get();
            if (delay == 0) {
                applyJump();
            } else {
                pendingDelay = delay;
                // used++ happens in applyJump; don't pre-increment
            }
        }
        wasPressed = pressed;
    }

    private void applyJump() {
        double jit = (Math.random() * 2 - 1) * jitter.get();
        double vel = jumpVelocity.get() + jit;
        mc.player.setVelocity(mc.player.getVelocity().x, vel, mc.player.getVelocity().z);
        used++;
        obs.markSent();
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
