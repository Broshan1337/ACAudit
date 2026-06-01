package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: Stress Runner (automated resilience sweep)
 *
 * Runs each named load module in turn for a fixed window, samples min TPS, and
 * prints a PASS/FAIL table at the end - "does the server hold under each abuse
 * vector?" without babysitting one soak-test at a time. A disconnect during any
 * vector is an automatic FAIL for that vector.
 *
 * Run against your OWN local server. Reads health from ServerHealthMonitor
 * (auto-enabled).
 */
public class StressRunner extends Module {
    private enum Phase { RUN, GAP, DONE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<String> targets = sgGeneral.add(new StringSetting.Builder()
        .name("targets")
        .description("Comma-separated load module names to run in sequence.")
        .defaultValue("packet-spammer,movement-crash,arm-animation-flood,block-interaction-spam,offhand-swap-spam,metadata-flood")
        .build()
    );
    private final Setting<Integer> perTest = sgGeneral.add(new IntSetting.Builder()
        .name("seconds-per-test").defaultValue(10).range(2, 120).sliderRange(5, 60).build()
    );
    private final Setting<Integer> gap = sgGeneral.add(new IntSetting.Builder()
        .name("gap-seconds").description("Recovery gap between tests.")
        .defaultValue(3).range(0, 30).sliderRange(0, 15).build()
    );
    private final Setting<Double> tpsFloor = sgGeneral.add(new DoubleSetting.Builder()
        .name("tps-floor").defaultValue(19.0).range(0.0, 20.0).sliderRange(10.0, 20.0).build()
    );

    private final List<String> names = new ArrayList<>();
    private final List<String> report = new ArrayList<>();
    private ServerHealthMonitor health;
    private Phase phase;
    private int index;
    private long phaseStart;
    private Module current;
    private double minTps;

    public StressRunner() {
        super(AddonTemplate.TESTING_CATEGORY, "stress-runner",
            "Runs each named load module in sequence and reports per-vector PASS/FAIL on server TPS. Automated resilience sweep.");
    }

    @Override
    public void onActivate() {
        health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && !health.isActive()) health.toggle();

        names.clear(); report.clear();
        for (String s : targets.get().split(",")) { String t = s.trim(); if (!t.isEmpty()) names.add(t); }
        if (names.isEmpty()) { error("No targets. Disabling."); toggle(); return; }

        index = -1;
        info("Stress sweep: %d vectors, %ds each.", names.size(), perTest.get());
        startNext();
    }

    @Override
    public void onDeactivate() { stopCurrent(); }

    private void startNext() {
        index++;
        if (index >= names.size()) { finish(); return; }
        current = Modules.get().get(names.get(index));
        if (current == null) {
            report.add(String.format("  %-26s SKIP (no such module)", names.get(index)));
            startNext();
            return;
        }
        if (!current.isActive()) current.toggle();
        minTps = 20.0;
        phase = Phase.RUN;
        phaseStart = System.currentTimeMillis();
        info("[%d/%d] running '%s'…", index + 1, names.size(), names.get(index));
    }

    private void stopCurrent() {
        if (current != null && current.isActive()) current.toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (phase == Phase.DONE || health == null) return;
        long elapsed = System.currentTimeMillis() - phaseStart;

        if (phase == Phase.RUN) {
            if (health.isWarm()) minTps = Math.min(minTps, health.getTps());
            if (elapsed >= perTest.get() * 1000L) {
                stopCurrent();
                boolean pass = minTps >= tpsFloor.get();
                report.add(String.format("  %-26s min TPS %.1f  %s",
                    names.get(index), minTps, pass ? "PASS" : "FAIL"));
                phase = Phase.GAP;
                phaseStart = System.currentTimeMillis();
            }
        } else if (phase == Phase.GAP) {
            if (elapsed >= gap.get() * 1000L) startNext();
        }
    }

    private void finish() {
        phase = Phase.DONE;
        info("==== Stress Runner Report (floor %.1f) ====", tpsFloor.get());
        for (String line : report) info(line);
        toggle();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (phase == Phase.RUN) {
            report.add(String.format("  %-26s DISCONNECTED (FAIL)", names.get(index)));
            warning("Disconnected during '%s'.", names.get(index));
        }
        if (isActive()) toggle();
    }
}
