package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: StealthFly
 *
 * A fly module built specifically to probe the blind spots of threshold-based
 * anti-cheats. Each mode violates physics while keeping a different "symptom"
 * metric (per-tick delta, instantaneous speed, ground flag) inside legal range.
 *
 *   SUB_THRESHOLD  ascend a tiny amount every tick (default 0.04 b/t).
 *   STATIC_HOVER   hold an exact Y (zero motion).
 *   GLIDE          fall slower than gravity allows.
 *   JITTER         oscillate ± around a height so each delta is tiny.
 *
 * Subtlety controls (across modes):
 *   ascend-jitter       — ±noise on ascend-per-tick (SUB_THRESHOLD). Tests whether
 *                         the AC sums the window or only flags the per-tick value.
 *   glide-fall-jitter   — ±noise on glide-fall (GLIDE). Tests gravity-sim precision.
 *   jitter-period-min/max — JITTER oscillation period varies in [min,max] ticks
 *                         instead of every tick. Tests low-pass / averaging detection.
 *   skip-probability    — occasionally skip the override and let natural gravity
 *                         apply. Tests whether intermittent fly is caught.
 *
 * Combination: StealthFly+Blink (fly during silence, flush movement burst).
 * StealthFly+AntiSetback (absorb corrections while hovering).
 */
public class StealthFly extends Module {
    public enum Mode { SUB_THRESHOLD, STATIC_HOVER, GLIDE, JITTER }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which evasion strategy to test.")
        .defaultValue(Mode.SUB_THRESHOLD).build()
    );
    private final Setting<Double> ascendPerTick = sgGeneral.add(new DoubleSetting.Builder()
        .name("ascend-per-tick")
        .description("Y gained per tick in SUB_THRESHOLD mode.")
        .defaultValue(0.04).range(0.001, 0.5).sliderRange(0.01, 0.2)
        .visible(() -> mode.get() == Mode.SUB_THRESHOLD).build()
    );
    private final Setting<Double> ascendJitter = sgGeneral.add(new DoubleSetting.Builder()
        .name("ascend-jitter")
        .description("Random ±noise on ascend-per-tick (SUB_THRESHOLD). Tests window-sum vs. per-tick threshold.")
        .defaultValue(0.005).range(0.0, 0.05).sliderRange(0.0, 0.03)
        .visible(() -> mode.get() == Mode.SUB_THRESHOLD).build()
    );
    private final Setting<Double> glideFall = sgGeneral.add(new DoubleSetting.Builder()
        .name("glide-fall")
        .description("Downward speed in GLIDE mode (vanilla terminal ~-0.08/tick early fall).")
        .defaultValue(0.03).range(0.0, 0.5).sliderRange(0.0, 0.2)
        .visible(() -> mode.get() == Mode.GLIDE).build()
    );
    private final Setting<Double> glideFallJitter = sgGeneral.add(new DoubleSetting.Builder()
        .name("glide-fall-jitter")
        .description("Random ±noise on glide-fall per tick (GLIDE). Tests gravity-simulation precision.")
        .defaultValue(0.005).range(0.0, 0.05).sliderRange(0.0, 0.03)
        .visible(() -> mode.get() == Mode.GLIDE).build()
    );
    private final Setting<Double> jitterAmount = sgGeneral.add(new DoubleSetting.Builder()
        .name("jitter")
        .description("Up/down oscillation amplitude in JITTER mode.")
        .defaultValue(0.05).range(0.01, 0.4).sliderRange(0.01, 0.2)
        .visible(() -> mode.get() == Mode.JITTER).build()
    );
    private final Setting<Integer> jitterPeriodMin = sgGeneral.add(new IntSetting.Builder()
        .name("jitter-period-min")
        .description("Min ticks per oscillation direction (JITTER). Tests low-pass / averaging detection.")
        .defaultValue(1).range(1, 5).sliderRange(1, 4)
        .visible(() -> mode.get() == Mode.JITTER).build()
    );
    private final Setting<Integer> jitterPeriodMax = sgGeneral.add(new IntSetting.Builder()
        .name("jitter-period-max")
        .description("Max ticks per oscillation direction (JITTER). Must be >= jitter-period-min.")
        .defaultValue(3).range(1, 8).sliderRange(1, 6)
        .visible(() -> mode.get() == Mode.JITTER).build()
    );
    private final Setting<Double> skipProbability = sgGeneral.add(new DoubleSetting.Builder()
        .name("skip-probability")
        .description("Chance per tick to skip the fly override (let gravity apply). Tests intermittent fly detection.")
        .defaultValue(0.0).range(0.0, 0.25).sliderRange(0.0, 0.2).build()
    );
    private final Setting<Boolean> spoofGround = sgGeneral.add(new BoolSetting.Builder()
        .name("spoof-on-ground")
        .description("Send onGround=true while airborne. Defeats ACs that trust the client ground flag.")
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
    private double holdY = 0;
    private boolean jitterUp = true;
    private int jitterTicksRemaining = 0;

    public StealthFly() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "stealth-fly",
            "Fly variants that stay under threshold checks. Probes AC blind spots (per-tick delta, ground flag, gravity sim).");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        if (mc.player != null) holdY = mc.player.getY();
        jitterUp = true; jitterTicksRemaining = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;

        if (skipProbability.get() > 0 && Math.random() < skipProbability.get()) return;

        double x = mc.player.getX(), z = mc.player.getZ();
        double targetY;

        switch (mode.get()) {
            case SUB_THRESHOLD -> {
                double jit = ascendJitter.get() > 0 ? (Math.random() * 2 - 1) * ascendJitter.get() : 0;
                holdY += ascendPerTick.get() + jit;
                targetY = holdY;
                mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);
            }
            case STATIC_HOVER -> {
                targetY = holdY;
                mc.player.setVelocity(new Vec3d(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z));
            }
            case GLIDE -> {
                double jit = glideFallJitter.get() > 0 ? (Math.random() * 2 - 1) * glideFallJitter.get() : 0;
                double fall = glideFall.get() + jit;
                holdY -= fall;
                targetY = holdY;
                mc.player.setVelocity(mc.player.getVelocity().x, -fall, mc.player.getVelocity().z);
            }
            case JITTER -> {
                if (jitterTicksRemaining <= 0) {
                    jitterUp = !jitterUp;
                    int min = jitterPeriodMin.get(), max = Math.max(min, jitterPeriodMax.get());
                    jitterTicksRemaining = min + (int)(Math.random() * (max - min + 1));
                }
                jitterTicksRemaining--;
                holdY += jitterUp ? jitterAmount.get() : -jitterAmount.get();
                targetY = holdY;
            }
            default -> { return; }
        }

        mc.player.setPosition(x, targetY, z);
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            x, targetY, z, spoofGround.get(), mc.player.horizontalCollision));
        packetsSent++;
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
