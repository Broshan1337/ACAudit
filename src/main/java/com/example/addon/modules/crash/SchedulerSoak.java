package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Scheduler Soak (gradual degradation — the slow, hard-to-detect class)
 *
 * Sudden crashes are easy to notice; SLOW degradation is not. This runs a chosen command
 * at a low, SUB-LIMITER rate over a long window and watches whether TPS DRIFTS downward as
 * the plugin accumulates work — scheduled async tasks that never drain, unbounded caches,
 * per-command allocations that outpace GC, connection-pool checkouts that leak. A patient
 * attacker uses exactly this: stay under every rate limit, but never let the server catch
 * up. A vector that floors TPS in one tick gets noticed and patched; one that costs 0.5 TPS
 * every minute runs for hours.
 *
 * <p>It captures a baseline TPS, sustains the load, and reports the DRIFT (baseline → final)
 * plus min TPS — a downward drift with no recovery is the finding, even if TPS never hits a
 * crash threshold.
 *
 * <p>Patch signal: bound every per-command async task queue and reject when full; cap and
 * expire caches; return pooled resources deterministically; alert on monotonic TPS decline,
 * not just on a hard floor.
 *
 * <p>Realism / severity / safe intensity / what to watch print on activate. Own server only.
 */
public class SchedulerSoak extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("Command to sustain at a sub-limiter rate (no leading slash). Pick one that schedules server-side work.")
        .defaultValue("sell all").build());
    private final Setting<Integer> perSecond = sgGeneral.add(new IntSetting.Builder()
        .name("commands-per-second").description("Sustained rate — keep it BELOW the server's rate limit so it runs indefinitely.")
        .defaultValue(4).range(1, 40).sliderRange(1, 20).build());
    private final Setting<Integer> durationSecs = sgGeneral.add(new IntSetting.Builder()
        .name("duration-seconds").description("How long to soak. Longer reveals slower drift.")
        .defaultValue(120).range(10, 3600).sliderRange(30, 600).build());

    private final PreStress preStress = new PreStress(sgGeneral);
    private final GracefulResponse gr = new GracefulResponse(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.").defaultValue(true).build());
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print the drift report on deactivate.").defaultValue(true).build());

    private int ticksActive = 0, packetsSent = 0, tickInPeriod = 0;
    private long startMs;
    private double baselineTps = -1, finalTps = -1, minTps = 20.0;
    private ServerHealthMonitor health;

    public SchedulerSoak() {
        super(AddonTemplate.CRASH_CATEGORY, "scheduler-soak",
            "Sustains a command at a sub-limiter rate over a long window and reports TPS DRIFT. Tests slow, hard-to-detect degradation (task/cache/pool accumulation).");
    }

    @Override
    public void onActivate() {
        ticksActive = packetsSent = tickInPeriod = 0;
        baselineTps = finalTps = -1; minTps = 20.0;
        startMs = System.currentTimeMillis();
        health = Modules.get().get(ServerHealthMonitor.class);
        gr.onActivate(this); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!gr.firingAllowed()) return;

        // Sample TPS drift.
        if (health != null && health.isActive() && health.isWarm()) {
            double tps = health.getTps();
            if (baselineTps < 0 && ticksActive > 20) baselineTps = tps;   // settle a second before baseline
            finalTps = tps;
            minTps = Math.min(minTps, tps);
        }

        // Sustain the command at the configured per-second rate (spread across 20 ticks).
        int period = Math.max(1, 20 / Math.max(1, perSecond.get()));
        if (++tickInPeriod >= period) {
            tickInPeriod = 0;
            mc.player.networkHandler.sendChatCommand(command.get().trim());
            packetsSent++;
            gr.markFired();
        }

        if (System.currentTimeMillis() - startMs >= durationSecs.get() * 1000L) {
            info("Soak window (%ds) complete.", durationSecs.get());
            if (isActive()) toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("=== Scheduler Soak report ===");
            info("  %d commands over %ds.", packetsSent, durationSecs.get());
            if (baselineTps >= 0 && finalTps >= 0) {
                double drift = finalTps - baselineTps;
                info("  TPS: baseline %.1f -> final %.1f (drift %+.1f), min %.1f.", baselineTps, finalTps, drift, minTps);
                if (drift < -1.0)
                    warning("  [HIGH] TPS drifted DOWN %.1f over the soak with no recovery — slow degradation (task/cache/pool accumulation). INVESTIGATE.", -drift);
                else if (minTps < 18.0)
                    info("  TPS dipped to %.1f but did not drift — transient, not accumulation.", minTps);
                else
                    info("  No downward drift — the plugin drained its work each period (good).");
            } else {
                info("  TPS not sampled (server-health-monitor unavailable).");
            }
            gr.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { gr.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        gr.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
