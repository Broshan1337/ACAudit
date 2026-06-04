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
 * AUDIT: Baseline Poison (behavioral-profile continuation probe)
 *
 * Targets the PROFILE/heuristic class of anticheat — the kind that learns a
 * per-player behavioral baseline and flags DEVIATIONS from it, rather than
 * validating each tick against absolute physics. The hypothesis: once such an AC
 * has learned a player's pattern, a cheat introduced as a smooth CONTINUATION of
 * that pattern (a slow drift) may never present the sudden deviation the profile is
 * watching for.
 *
 * The module behaves cleanly for a baseline window so the AC learns the pattern,
 * then ramps a speed delta in gradually (via BaselineProfiler) instead of stepping
 * it, and grades via MovementObserver whether the drifted delta is corrected.
 *
 * IMPORTANT honest scope: against a DETERMINISTIC physics-simulation AC this should
 * NOT help — every tick is validated against absolute physics regardless of how
 * gradually the delta arrives. A pass here against a physics AC means the delta is
 * simply within tolerance; a pass against a profiling AC where the same delta
 * stepped-in flags is the real finding.
 *
 *   Patch signal (any well-implemented profiling AC): anchor the behavioral
 *     baseline to physically legal bounds, not solely to the player's own recent
 *     history, so a slow drift cannot relocate "normal" into illegal territory.
 *     Measure deviations against ground truth, not a baseline the player can move.
 *
 * Run on YOUR server; compare a ramped run against a stepped run of the same delta.
 */
public class BaselinePoison extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> maxDelta = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-delta").description("Final extra horizontal speed (b/t) the ramp drifts toward.")
        .defaultValue(0.12).range(0.0, 0.6).sliderRange(0.0, 0.3).build()
    );

    private final BaselineProfiler profile = new BaselineProfiler(sgGeneral);
    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0;

    public BaselinePoison() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "baseline-poison",
            "Behaves cleanly to establish a baseline, then drifts a speed delta in as a continuation. Probes the profile/heuristic AC class (not deterministic-physics ACs).");
    }

    @Override
    public void onActivate() { ticksActive = 0; profile.onActivate(); obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        profile.tick(l -> info("%s", l));

        double delta = maxDelta.get() * profile.rampFraction();
        if (delta <= 0) return; // baseline phase: behave cleanly

        float fwd = mc.player.forwardSpeed, side = mc.player.sidewaysSpeed;
        if (fwd == 0 && side == 0) return;
        double len = Math.sqrt(fwd * fwd + side * side);
        double f = fwd / len, s = side / len;
        double yaw = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        Vec3d v = mc.player.getVelocity();
        double base = 0.2806;
        double mag = base + delta;
        mc.player.setVelocity((f * -sin + s * cos) * mag, v.y, (f * cos + s * sin) * mag);
        obs.markSent();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active.", ticksActive);
            profile.report(l -> info("%s", l));
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
