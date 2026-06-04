package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Speed (Strafe)
 *
 * Overrides horizontal velocity to a fixed speed in the player's input
 * direction. Vanilla ground speed is ~0.21 b/t walking, ~0.28 sprinting; any
 * sustained value above that is the most common horizontal-speed flag.
 *
 * Subtlety controls:
 *   jitter-factor — effective speed = speed * (1 ± jitter) per tick. Tests
 *                   variance-based horizontal speed detection.
 *   ramp-ticks    — ramp from vanilla sprint (~0.28) to the target speed over N
 *                   ticks of continuous movement. Tests whether the AC catches
 *                   gradual acceleration or only sudden speed changes.
 *                   Progress resets when the player stops moving.
 *
 * Re-derived from the strafe math LiquidBounce's speed modes use.
 */
public class Speed extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("Target blocks per tick (vanilla sprint ~0.28).")
        .defaultValue(0.5).range(0.1, 5.0).sliderRange(0.21, 1.0).build()
    );
    private final Setting<Double> jitterFactor = sgGeneral.add(new DoubleSetting.Builder()
        .name("jitter-factor")
        .description("Random ±fraction applied to speed each tick. Tests variance-based speed detection.")
        .defaultValue(0.0).range(0.0, 0.3).sliderRange(0.0, 0.2).build()
    );
    private final Setting<Integer> rampTicks = sgGeneral.add(new IntSetting.Builder()
        .name("ramp-ticks")
        .description("Ticks to ramp from vanilla sprint to target speed (0 = instant). Progress resets on stop.")
        .defaultValue(0).range(0, 10).sliderRange(0, 8).build()
    );
    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-ground").description("Only boost while on the ground.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> adaptiveSeek = sgGeneral.add(new BoolSetting.Builder()
        .name("adaptive-seek")
        .description("Start near vanilla and raise speed every seek-interval until the server sets you back, then back off — finds the EXACT detection threshold instead of guessing.")
        .defaultValue(false).build()
    );
    private final Setting<Double> seekStep = sgGeneral.add(new DoubleSetting.Builder()
        .name("seek-step").description("Speed increment per interval while seeking (b/t).")
        .defaultValue(0.02).range(0.005, 0.2).sliderRange(0.005, 0.1)
        .visible(adaptiveSeek::get).build()
    );
    private final Setting<Integer> seekInterval = sgGeneral.add(new IntSetting.Builder()
        .name("seek-interval").description("Ticks at each speed before stepping up.")
        .defaultValue(20).range(2, 200).sliderRange(5, 80)
        .visible(adaptiveSeek::get).build()
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
    private double rampProgress = 0.0;
    private double seekSpeed = 0.28;
    private int seekTimer = 0, seekLastSetbacks = 0;
    private boolean seekFound = false;

    public Speed() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "speed",
            "Boosts horizontal velocity in the input direction. Tests horizontal-speed detection.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; rampProgress = 0.0;
        seekSpeed = 0.28; seekTimer = 0; seekLastSetbacks = 0; seekFound = false;
        obs.onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        if (onlyOnGround.get() && !mc.player.isOnGround()) return;

        float forward  = mc.player.forwardSpeed;
        float sideways = mc.player.sidewaysSpeed;
        if (forward == 0 && sideways == 0) { rampProgress = 0.0; return; }

        double len = Math.sqrt(forward * forward + sideways * sideways);
        double f = forward / len, s = sideways / len;

        double yaw = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);

        double target = speed.get();
        if (adaptiveSeek.get()) {
            // Step the seek speed up until the server starts setting us back, then back off once.
            if (!seekFound) {
                if (obs.setbackCount() > seekLastSetbacks) {
                    seekFound = true;
                    seekSpeed = Math.max(0.28, seekSpeed - seekStep.get());
                    info("Adaptive-seek: detection threshold ≈ %.3f b/t (backed off to %.3f).",
                        seekSpeed + seekStep.get(), seekSpeed);
                } else if (++seekTimer >= seekInterval.get()) {
                    seekTimer = 0;
                    seekSpeed += seekStep.get();
                }
                seekLastSetbacks = obs.setbackCount();
            }
            target = seekSpeed;
        }
        double jit = jitterFactor.get() > 0 ? (Math.random() * 2 - 1) * jitterFactor.get() : 0;
        double effective = target * (1.0 + jit);

        int ramp = rampTicks.get();
        if (ramp > 0 && !adaptiveSeek.get()) {
            rampProgress = Math.min(1.0, rampProgress + 1.0 / ramp);
            double vanilla = 0.28;
            effective = vanilla + (effective - vanilla) * rampProgress;
        }

        double velX = (f * -sin + s * cos) * effective;
        double velZ = (f *  cos + s * sin) * effective;

        Vec3d v = mc.player.getVelocity();
        mc.player.setVelocity(velX, v.y, velZ);
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
