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
 * Pair each mode with the detection note to learn where your AC actually checks.
 *
 *   SUB_THRESHOLD  ascend a tiny amount every tick (default 0.04 b/t).
 *                  Detection: sum vertical gain over a window; any net climb in
 *                  air with no ground contact is illegal regardless of per-tick size.
 *
 *   STATIC_HOVER   hold an exact Y, resend identical positions (zero motion).
 *                  Detection: flag "in air, zero vertical velocity, no support block".
 *
 *   GLIDE          fall slower than gravity allows (looks like slow-fall).
 *                  Detection: compare fall speed to gravity simulation for this
 *                  player's effect state; reject sub-gravity descent without slow-fall.
 *
 *   JITTER         oscillate +/- around a height so each delta is tiny but the
 *                  net result is sustained flight.
 *                  Detection: low-pass the position over N ticks; the average
 *                  still shows no ground contact and unnatural vertical hold.
 *
 * All modes optionally spoof onGround=true, which by itself defeats any AC that
 * trusts the client ground flag instead of raycasting the block below.
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
        .description("Y gained per tick in SUB_THRESHOLD mode (keep small to stay under flag thresholds).")
        .defaultValue(0.04).range(0.001, 0.5).sliderRange(0.01, 0.2)
        .visible(() -> mode.get() == Mode.SUB_THRESHOLD).build()
    );
    private final Setting<Double> glideFall = sgGeneral.add(new DoubleSetting.Builder()
        .name("glide-fall")
        .description("Downward speed in GLIDE mode (vanilla terminal is ~ -0.08/tick early fall).")
        .defaultValue(0.03).range(0.0, 0.5).sliderRange(0.0, 0.2)
        .visible(() -> mode.get() == Mode.GLIDE).build()
    );
    private final Setting<Double> jitterAmount = sgGeneral.add(new DoubleSetting.Builder()
        .name("jitter")
        .description("Up/down oscillation amplitude in JITTER mode.")
        .defaultValue(0.05).range(0.01, 0.4).sliderRange(0.01, 0.2)
        .visible(() -> mode.get() == Mode.JITTER).build()
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

    public StealthFly() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "stealth-fly",
            "Fly variants that stay under threshold checks. Probes AC blind spots (per-tick delta, ground flag, gravity sim).");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) holdY = mc.player.getY();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;

        double x = mc.player.getX();
        double z = mc.player.getZ();
        double targetY;

        switch (mode.get()) {
            case SUB_THRESHOLD -> {
                holdY += ascendPerTick.get();
                targetY = holdY;
                mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);
            }
            case STATIC_HOVER -> {
                targetY = holdY;
                mc.player.setVelocity(new Vec3d(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z));
            }
            case GLIDE -> {
                holdY -= glideFall.get();
                targetY = holdY;
                mc.player.setVelocity(mc.player.getVelocity().x, -glideFall.get(), mc.player.getVelocity().z);
            }
            case JITTER -> {
                holdY += jitterUp ? jitterAmount.get() : -jitterAmount.get();
                jitterUp = !jitterUp;
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
