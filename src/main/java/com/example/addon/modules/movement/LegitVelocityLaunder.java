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
 * AUDIT: Legit-Velocity Launder (velocity-source decay probe — axis 3)
 *
 * Every legitimate high-speed source — knockback, entity push, piston, water
 * current, ice/slime — gives the player velocity the AC must accept. The cheat is
 * not generating that velocity; it is RETAINING it past the point physics would
 * have decayed it. This watches the player's real velocity, and when it exceeds a
 * walk-speed threshold (a legitimate source just pushed us), it LATCHES that
 * velocity vector and keeps re-applying it for N extra ticks beyond natural decay.
 *
 *   What it exploits: that the AC tracks the APPLICATION of a legitimate velocity
 *     but not its DECAY curve.
 *   Measurement AC: accepts — the speed traces back to a real server-side source.
 *   Physics AC: models friction/drag decay and flags speed retained past the
 *     decay window.
 *   Intent AC: flags the too-convenient positioning that keeps catching pushes.
 *   Fix: track the decay of every legitimate velocity source, not just its onset;
 *     expected speed at tick t after a push is bounded by the drag model.
 *
 * Also documents the FALSE-POSITIVE side: enable retain-ticks=0 and simply ride
 * ice/a current at legitimate high speed to confirm the AC does NOT flag genuine
 * high-speed block movement (a great AC models ice physics correctly).
 *
 * Stand where a real source will push you (KB, current, ice). Run on YOUR server.
 */
public class LegitVelocityLaunder extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> threshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("latch-threshold").description("Horizontal speed (b/t) above which a legit source is assumed and latched.")
        .defaultValue(0.30).range(0.1, 1.0).sliderRange(0.2, 0.8).build()
    );
    private final Setting<Integer> retainTicks = sgGeneral.add(new IntSetting.Builder()
        .name("retain-ticks").description("Extra ticks to hold the latched velocity past natural decay (0 = ride legit only).")
        .defaultValue(10).range(0, 60).sliderRange(0, 30).build()
    );
    private final Setting<Double> decayPerTick = sgGeneral.add(new DoubleSetting.Builder()
        .name("retain-decay").description("Fraction the latched velocity keeps each retained tick (1 = no decay).")
        .defaultValue(0.98).range(0.5, 1.0).sliderRange(0.8, 1.0).build()
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
    private Vec3d latched = null;
    private int retainLeft = 0;

    public LegitVelocityLaunder() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "legit-velocity-launder",
            "Latches a legitimate velocity source and retains it past natural decay. Tests whether the AC models velocity decay, not just its source.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; latched = null; retainLeft = 0;
        obs.onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        Vec3d v = mc.player.getVelocity();
        double hSpeed = Math.hypot(v.x, v.z);

        // Latch when a legitimate source pushes us past the threshold.
        if (hSpeed >= threshold.get()) {
            latched = new Vec3d(v.x, v.y, v.z);
            retainLeft = retainTicks.get();
            return;
        }

        // Retain the latched velocity past where physics would have decayed it.
        if (latched != null && retainLeft > 0) {
            mc.player.setVelocity(latched.x, mc.player.getVelocity().y, latched.z);
            latched = new Vec3d(latched.x * decayPerTick.get(), latched.y, latched.z * decayPerTick.get());
            retainLeft--;
            packetsSent++;
            obs.markSent();
            if (retainLeft == 0) latched = null;
        }
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
