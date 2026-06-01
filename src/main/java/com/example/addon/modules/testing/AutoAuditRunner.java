package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: Auto-Audit Runner (sequential sweep with file report)
 *
 * Extends StressRunner with file output. Runs each named load module in turn for
 * a fixed window, samples min TPS, and writes a timestamped PASS/FAIL report to:
 *
 *     <mc-run-dir>/config/acaudit/reports/YYYY-MM-DD_HH-mm-ss.txt
 *
 * A disconnect during any vector is an automatic FAIL for that vector. After the
 * sweep completes (or the last module finishes) the runner disables itself and
 * leaves the report file ready for review.
 *
 * Unlike StressRunner, this module: saves results to disk, notes the disconnect
 * reason per vector, and prints the report path on completion.
 *
 * Run against your OWN local server only.
 */
public class AutoAuditRunner extends Module {
    private enum Phase { RUN, GAP, DONE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<String> targets = sgGeneral.add(new StringSetting.Builder()
        .name("targets")
        .description("Comma-separated module names to sweep in order.")
        .defaultValue("chunk-border-stress,packet-spammer,movement-crash,arm-animation-flood,entity-spam,channel-flood,nbt-bomb,book-crash,passenger-loop,snbt-depth")
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
    private String lastDisconnectReason = null;

    public AutoAuditRunner() {
        super(AddonTemplate.TESTING_CATEGORY, "auto-audit-runner",
            "Sequential module sweep with PASS/FAIL report saved to config/acaudit/reports/. Tests all named vectors in one run.");
    }

    @Override
    public void onActivate() {
        health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && !health.isActive()) health.toggle();

        names.clear(); report.clear(); lastDisconnectReason = null;
        for (String s : targets.get().split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) names.add(t);
        }
        if (names.isEmpty()) { error("No targets."); toggle(); return; }

        report.add("ACAudit Auto-Audit Report — " + timestamp());
        report.add("TPS floor: " + tpsFloor.get() + "  seconds/test: " + perTest.get());
        report.add("Vectors: " + names.size());
        report.add("---");

        index = -1;
        info("Auto-audit: %d vectors, %ds each.", names.size(), perTest.get());
        startNext();
    }

    @Override
    public void onDeactivate() {
        stopCurrent();
        if (!report.isEmpty()) saveReport();
    }

    private void startNext() {
        index++;
        if (index >= names.size()) { finish(); return; }
        current = Modules.get().get(names.get(index));
        if (current == null) {
            report.add(String.format("  %-32s SKIP (module not found)", names.get(index)));
            startNext(); return;
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
                report.add(String.format("  %-32s min TPS %.1f  %s", names.get(index), minTps, pass ? "PASS" : "FAIL"));
                phase = Phase.GAP; phaseStart = System.currentTimeMillis();
            }
        } else if (phase == Phase.GAP) {
            if (elapsed >= gap.get() * 1000L) startNext();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (phase == Phase.RUN) {
            String reason = lastDisconnectReason != null ? lastDisconnectReason : "unknown reason";
            report.add(String.format("  %-32s DISCONNECTED (%s) — FAIL", names.get(index), reason));
            warning("Disconnected during '%s'.", names.get(index));
        }
        if (isActive()) toggle();
    }

    private void finish() {
        phase = Phase.DONE;
        report.add("---");
        long pass = report.stream().filter(l -> l.contains("PASS")).count();
        long fail = report.stream().filter(l -> l.contains("FAIL")).count();
        report.add(String.format("Result: %d PASS  %d FAIL  %d SKIP", pass, fail,
            names.size() - (int)(pass + fail)));
        info("==== Auto-Audit Complete: %d PASS  %d FAIL ====", pass, fail);
        toggle();
    }

    private void saveReport() {
        try {
            File dir = new File(mc.runDirectory, "config/acaudit/reports");
            dir.mkdirs();
            File file = new File(dir, timestamp().replace(":", "-") + ".txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
                for (String line : report) pw.println(line);
            }
            info("Report saved: %s", file.getAbsolutePath());
        } catch (IOException e) {
            error("Failed to save report: %s", e.getMessage());
        }
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
