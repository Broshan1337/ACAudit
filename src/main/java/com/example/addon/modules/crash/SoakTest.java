package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Soak Test (resilience harness)
 *
 * Drives one of the existing load modules against the server for a fixed window
 * and asserts the server stayed healthy. This is the regression harness for the
 * structural invariants: under sustained legal-packet load, server TPS must hold
 * above the floor, ping must not blow up, and you must not be disconnected.
 *
 * Phases:
 *   WARMUP  - sample baseline TPS/ping with no load.
 *   LOAD    - enable the target module, sample min/avg TPS and max ping.
 *   REPORT  - disable target, print PASS/FAIL vs. thresholds, disable self.
 *
 * A disconnect during LOAD is an automatic FAIL (the load reached the server).
 * Run against a LOCAL test instance you own. Reads health from
 * ServerHealthMonitor, which it auto-enables.
 */
public class SoakTest extends Module {
    private enum Phase { WARMUP, LOAD, DONE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<String> targetModule = sgGeneral.add(new StringSetting.Builder()
        .name("target-module")
        .description("Name of the load module to run (e.g. movement-crash, packet-spammer, payload-flood).")
        .defaultValue("packet-spammer")
        .build()
    );
    private final Setting<Integer> warmupSecs = sgGeneral.add(new IntSetting.Builder()
        .name("warmup-seconds")
        .description("Baseline sampling before load starts.")
        .defaultValue(5).range(1, 60).sliderRange(1, 20).build()
    );
    private final Setting<Integer> loadSecs = sgGeneral.add(new IntSetting.Builder()
        .name("load-seconds")
        .description("How long to run the load module.")
        .defaultValue(15).range(1, 600).sliderRange(5, 120).build()
    );
    private final Setting<Double> tpsFloor = sgGeneral.add(new DoubleSetting.Builder()
        .name("tps-floor")
        .description("PASS requires min TPS to stay at or above this.")
        .defaultValue(19.0).range(0.0, 20.0).sliderRange(10.0, 20.0).build()
    );
    private final Setting<Integer> pingCeiling = sgGeneral.add(new IntSetting.Builder()
        .name("ping-ceiling-ms")
        .description("PASS requires max ping to stay at or below this (0 = ignore).")
        .defaultValue(0).range(0, 10000).sliderRange(0, 1000).build()
    );

    private Phase phase;
    private long phaseStartMs;
    private Module target;
    private ServerHealthMonitor health;

    // metrics
    private double baselineTps, minTps, tpsSum;
    private int samples, maxPing, baselinePing;
    private boolean disconnected;

    public SoakTest() {
        super(AddonTemplate.TESTING_CATEGORY, "soak-test",
            "Runs a load module for a fixed window and asserts server TPS/ping/connection held. Resilience regression harness.");
    }

    @Override
    public void onActivate() {
        // resolve and enable the health monitor
        health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && !health.isActive()) health.toggle();

        // resolve the target load module by name
        target = Modules.get().get(targetModule.get());
        if (target == null) {
            error("No module named '%s'. Disabling.", targetModule.get());
            toggle();
            return;
        }
        if (target == this) {
            error("target-module cannot be soak-test. Disabling.");
            toggle();
            return;
        }

        phase = Phase.WARMUP;
        phaseStartMs = System.currentTimeMillis();
        minTps = 20.0; tpsSum = 0; samples = 0; maxPing = 0; disconnected = false;
        info("Soak test starting: warmup %ds, load '%s' %ds, TPS floor %.1f.",
            warmupSecs.get(), targetModule.get(), loadSecs.get(), tpsFloor.get());
    }

    @Override
    public void onDeactivate() {
        // never leave the load running if we stop early
        if (target != null && target.isActive()) target.toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (health == null || phase == Phase.DONE) return;
        long elapsed = System.currentTimeMillis() - phaseStartMs;

        switch (phase) {
            case WARMUP -> {
                if (elapsed >= warmupSecs.get() * 1000L) {
                    baselineTps  = health.getTps();
                    baselinePing = health.getPing();
                    info("Baseline: TPS ~%.1f, ping %dms. Starting load '%s'.",
                        baselineTps, baselinePing, targetModule.get());
                    if (!target.isActive()) target.toggle();
                    phase = Phase.LOAD;
                    phaseStartMs = System.currentTimeMillis();
                }
            }
            case LOAD -> {
                double tps = health.getTps();
                int ping = health.getPing();
                if (health.isWarm()) {
                    minTps = Math.min(minTps, tps);
                    tpsSum += tps;
                    samples++;
                }
                if (ping > maxPing) maxPing = ping;

                if (elapsed >= loadSecs.get() * 1000L) finish();
            }
            default -> {}
        }
    }

    private void finish() {
        if (target != null && target.isActive()) target.toggle();
        phase = Phase.DONE;

        double avgTps = samples > 0 ? tpsSum / samples : 0;
        boolean tpsOk  = minTps >= tpsFloor.get();
        boolean pingOk = pingCeiling.get() == 0 || maxPing <= pingCeiling.get();
        boolean pass = tpsOk && pingOk && !disconnected;

        info("==== Soak Test Report: %s ====", targetModule.get());
        info("  min TPS %.1f (floor %.1f) %s", minTps, tpsFloor.get(), tpsOk ? "OK" : "FAIL");
        info("  avg TPS %.1f (baseline %.1f)", avgTps, baselineTps);
        info("  max ping %dms (baseline %dms)%s", maxPing, baselinePing,
            pingCeiling.get() == 0 ? "" : (pingOk ? " OK" : " FAIL"));
        info("  disconnected during load: %s", disconnected ? "YES (FAIL)" : "no");
        if (pass) info("  RESULT: PASS - server absorbed the load.");
        else warning("  RESULT: FAIL - investigate the invariant for this vector.");

        toggle();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (phase == Phase.LOAD) {
            disconnected = true;
            warning("Disconnected during load phase - automatic FAIL for '%s'.", targetModule.get());
            finish();  // prints the FAIL report before the module deactivates
        } else if (isActive()) {
            toggle();
        }
    }
}
