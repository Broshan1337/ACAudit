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
 * AUDIT: Accumulator Soak (long-session violation-level integrity probe)
 *
 * Most cheat detection is tested in short windows. Real players play for hours, and
 * an AC's violation-level bookkeeping (accumulation, decay, reset) must stay
 * accurate the whole time. This module emits one identical small violation at a
 * fixed cadence for the entire session and watches whether the server's correction
 * rate stays constant — or drifts. A late-session DROP in corrections suggests VL
 * decay or accumulator error is letting the same violation through after enough
 * history; a late-session RISE suggests floating-point/accumulator creep building a
 * false positive. Either is a finding.
 *
 *   What it exploits: violation bookkeeping that is not stationary over a long
 *     session.
 *   Patch signal (any well-implemented AC): the response to an identical violation
 *     must be invariant to how long the session has run and how many packets have
 *     been processed; VL accumulation/decay must be bounded and reset cleanly.
 *
 * Leave running for a long session against your OWN server.
 */
public class AccumulatorSoak extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> hopInterval = sgGeneral.add(new IntSetting.Builder()
        .name("hop-interval").description("Ticks between identical small violations.")
        .defaultValue(40).range(5, 400).sliderRange(10, 200).build()
    );
    private final Setting<Double> hopSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("hop-speed").description("Horizontal speed (b/t) of the identical violation hop.")
        .defaultValue(0.45).range(0.3, 2.0).sliderRange(0.3, 1.0).build()
    );
    private final Setting<Integer> windowSecs = sgGeneral.add(new IntSetting.Builder()
        .name("window-secs").description("Reporting window: prints corrections-per-hop each window so drift across the session is visible.")
        .defaultValue(120).range(15, 900).sliderRange(30, 600).build()
    );

    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private int tickTimer, windowHops, windowSetbackStart, windowIndex;
    private long activatedAt, windowStart;
    private double firstRate = -1, lastRate = -1;

    public AccumulatorSoak() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "accumulator-soak",
            "Emits one identical violation at a fixed cadence all session and tracks whether the correction rate drifts over time. VL/accumulator integrity instrument.");
    }

    @Override
    public void onActivate() {
        tickTimer = 0; windowHops = 0; windowSetbackStart = 0; windowIndex = 0;
        activatedAt = System.currentTimeMillis(); windowStart = activatedAt;
        firstRate = lastRate = -1;
        obs.onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        obs.tick();

        if (++tickTimer >= hopInterval.get()) {
            tickTimer = 0;
            float fwd = mc.player.forwardSpeed, side = mc.player.sidewaysSpeed;
            double f, s;
            if (fwd == 0 && side == 0) { f = 1; s = 0; }
            else { double len = Math.sqrt(fwd * fwd + side * side); f = fwd / len; s = side / len; }
            double yaw = Math.toRadians(mc.player.getYaw());
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            Vec3d v = mc.player.getVelocity();
            mc.player.setVelocity((f * -sin + s * cos) * hopSpeed.get(), v.y, (f * cos + s * sin) * hopSpeed.get());
            windowHops++;
            obs.markSent();
        }

        long now = System.currentTimeMillis();
        if (now - windowStart >= windowSecs.get() * 1000L) {
            int gained = obs.setbackCount() - windowSetbackStart;
            double rate = windowHops > 0 ? (double) gained / windowHops : 0;
            windowIndex++;
            info("Window %d (%.1f min): %d/%d hops corrected (rate %.2f).",
                windowIndex, (now - activatedAt) / 60000.0, gained, windowHops, rate);
            if (firstRate < 0) firstRate = rate;
            lastRate = rate;
            windowHops = 0; windowSetbackStart = obs.setbackCount(); windowStart = now;
        }
    }

    @Override
    public void onDeactivate() {
        if (firstRate >= 0 && lastRate >= 0) {
            info("Drift: first-window rate %.2f → last-window rate %.2f.", firstRate, lastRate);
            if (Math.abs(lastRate - firstRate) > 0.15)
                info("→ correction rate DRIFTED across the session: VL accumulator is not stationary. INVESTIGATE.");
            else
                info("→ correction rate stable across the session: accumulator held — good.");
        }
        obs.report(l -> info("%s", l));
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
