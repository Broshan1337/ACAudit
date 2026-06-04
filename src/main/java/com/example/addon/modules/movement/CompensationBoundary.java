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
 * AUDIT: Compensation Boundary (latency-compensation window probe)
 *
 * Latency compensation is legitimate: an AC widens its tolerance for a laggier
 * player so real network delay is not punished. But if the player can INFLATE the
 * latency the AC measures for them (here, by delaying transaction Pong replies via
 * TransactionAnchor), they widen their own tolerance on demand. This module holds a
 * fixed small illegal speed delta and ramps the injected transaction latency
 * upward until the server STOPS correcting the delta — reporting the latency at
 * which the compensation window grew large enough to swallow a cheat that was
 * corrected at honest timing.
 *
 *   What it exploits: compensation sized from a latency signal the client controls.
 *   Measurement AC: no compensation; result is flat (delta corrected regardless).
 *   Simulation AC: vulnerable if the compensation window is unbounded or sized
 *     purely from transaction RTT.
 *   Intent AC: caps the window and cross-checks transaction latency against an
 *     independent RTT, refusing extra tolerance when the signals disagree.
 *   Fix (any well-implemented AC): hard-cap the compensation window, and reconcile
 *     transaction-derived latency with transport/movement latency — grant the wider
 *     window only when ALL latency signals agree.
 *
 * Run against your OWN server only.
 */
public class CompensationBoundary extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> probeDelta = sgGeneral.add(new DoubleSetting.Builder()
        .name("probe-delta").description("Fixed extra horizontal speed (b/t) held throughout — should be illegal at honest timing so ramping latency is what (if anything) lets it through.")
        .defaultValue(0.12).range(0.02, 0.6).sliderRange(0.05, 0.3).build()
    );
    private final Setting<Integer> latencyStart = sgGeneral.add(new IntSetting.Builder()
        .name("latency-start").description("Injected Pong latency (ms) to begin at.")
        .defaultValue(0).range(0, 5000).sliderRange(0, 1000).build()
    );
    private final Setting<Integer> latencyStep = sgGeneral.add(new IntSetting.Builder()
        .name("latency-step").description("Increase injected latency by this many ms each interval until the delta is absorbed.")
        .defaultValue(100).range(10, 2000).sliderRange(25, 500).build()
    );
    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval").description("Ticks to hold each latency level before stepping up.")
        .defaultValue(20).range(2, 200).sliderRange(5, 80).build()
    );
    private final Setting<Integer> latencyCap = sgGeneral.add(new IntSetting.Builder()
        .name("latency-cap").description("Stop ramping at this injected latency (ms) — if the delta is still corrected here, compensation held.")
        .defaultValue(2000).range(100, 10000).sliderRange(500, 5000).build()
    );

    private final TransactionAnchor anchor = new TransactionAnchor(sgGeneral);
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
    private int curLatency, timer, lastSetbacks;
    private boolean found, ramped;

    public CompensationBoundary() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "compensation-boundary",
            "Holds a fixed illegal speed delta and ramps injected transaction latency until the compensation window absorbs it. Reports the window boundary.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; timer = 0; lastSetbacks = 0; found = false; ramped = false;
        curLatency = latencyStart.get();
        anchor.onActivate(); obs.onActivate();
        anchor.setInjectOverrideMs(curLatency);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        anchor.tick();

        // Hold the fixed illegal delta in the input/look direction.
        float fwd = mc.player.forwardSpeed, side = mc.player.sidewaysSpeed;
        double f, s;
        if (fwd == 0 && side == 0) { f = 1; s = 0; }
        else { double len = Math.sqrt(fwd * fwd + side * side); f = fwd / len; s = side / len; }
        double yaw = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        Vec3d v = mc.player.getVelocity();
        double base = 0.2806; // vanilla sprint
        double mag = base + probeDelta.get();
        mc.player.setVelocity((f * -sin + s * cos) * mag, v.y, (f * cos + s * sin) * mag);
        obs.markSent();

        if (found) return;
        if (++timer < interval.get()) return;
        timer = 0;
        int sb = obs.setbackCount();
        int delta = sb - lastSetbacks;
        lastSetbacks = sb;

        if (delta > 0) {
            // still corrected at this latency: ramp up (until the cap)
            if (curLatency >= latencyCap.get()) {
                found = true;
                info("Compensation HELD: delta %.3f still corrected at %d ms injected latency (cap). Window is bounded — good.", probeDelta.get(), curLatency);
            } else {
                curLatency = Math.min(latencyCap.get(), curLatency + latencyStep.get());
                anchor.setInjectOverrideMs(curLatency);
                ramped = true;
            }
        } else {
            found = true;
            if (ramped && curLatency > 0)
                info("Compensation WINDOW: delta %.3f corrected at honest timing became tolerated once injected latency reached ≈ %d ms — that slack is client-controllable. INVESTIGATE the window cap / RTT cross-check.", probeDelta.get(), curLatency);
            else
                info("Delta %.3f tolerated even at %d ms latency — it is within base tolerance; raise probe-delta to find the real boundary.", probeDelta.get(), curLatency);
        }
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) { anchor.onPongSend(event); }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.onReceive(event.packet);
        anchor.onReceive(event.packet);
    }

    @Override
    public void onDeactivate() {
        anchor.setInjectOverrideMs(-1);
        anchor.flush();
        if (showStats.get()) {
            info("Summary: %d ticks active, ended at %d ms injected latency.", ticksActive, curLatency);
            anchor.report(l -> info("%s", l));
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        anchor.flush();
        if (autoDisable.get() && isActive()) toggle();
    }
}
