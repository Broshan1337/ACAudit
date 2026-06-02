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
 * AUDIT: Vector Matrix (full-category sweep with file report)
 *
 * Runs ALL crash/dupe/movement modules in a pre-defined sweep list (or a custom
 * one) in sequence, records pass/fail per vector, and saves a full report to:
 *
 *     <mc-run-dir>/config/acaudit/reports/matrix_YYYY-MM-DD_HH-mm-ss.txt
 *
 * The default list covers the entire AuditAC-Crash tab. Customise with the
 * targets setting. Unlike StressRunner (custom list) and AutoAuditRunner (file
 * output only), VectorMatrix defaults to the full known crash vector inventory.
 *
 * Run against your OWN local server only.
 */
public class VectorMatrix extends Module {
    private enum Phase { RUN, GAP, DONE }

    // Full default list — crash vectors first, then dupe vectors
    private static final String DEFAULT_TARGETS =
        // Crash / stability
        "payload-flood,nbt-bomb,nan-position,extreme-velocity," +
        "block-interaction-spam,arm-animation-flood,sell-command-fuzz," +
        "position-crash,book-crash,completion-crash,container-crash," +
        "creative-crash,entity-crash,error-crash,interact-crash," +
        "lectern-crash,message-lagger,movement-crash,packet-spammer," +
        "sequence-crash,window-crash,fast-mine,fast-attack,ac-fast-use," +
        "chunk-border-stress,portal-spam,entity-spam,mount-crash," +
        "channel-flood,packet-order-chaos,snbt-depth,structure-string-flood," +
        "beacon-crash,passenger-loop," +
        // Dupe / inventory
        "slot-exploit,interaction-flood,drop-pickup-dupe,container-exploit," +
        "sell-race,auction-race,shulker-race,close-click,two-window-race," +
        "offhand-swap-spam,shift-click-race,drag-split-race,phantom-container," +
        "hopper-race,crafter-dupe,craft-grid-race,anvil-grindstone-race," +
        "bundle-dupe,death-inventory-race";

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<String> targets = sgGeneral.add(new StringSetting.Builder()
        .name("targets")
        .description("Comma-separated module names. Leave default for the full crash + dupe matrix.")
        .defaultValue(DEFAULT_TARGETS).build()
    );
    private final Setting<Integer> perTest = sgGeneral.add(new IntSetting.Builder()
        .name("seconds-per-test").defaultValue(8).range(2, 60).sliderRange(3, 30).build()
    );
    private final Setting<Integer> gapSecs = sgGeneral.add(new IntSetting.Builder()
        .name("gap-seconds").defaultValue(2).range(0, 20).sliderRange(0, 10).build()
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

    public VectorMatrix() {
        super(AddonTemplate.TESTING_CATEGORY, "vector-matrix",
            "Runs the full crash + dupe vector list in sequence and saves a PASS/FAIL matrix to config/acaudit/reports/.");
    }

    @Override
    public void onActivate() {
        health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && !health.isActive()) health.toggle();

        names.clear(); report.clear();
        for (String s : targets.get().split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) names.add(t);
        }
        if (names.isEmpty()) { error("No targets."); toggle(); return; }

        report.add("ACAudit Vector Matrix Report — " + ts());
        report.add(String.format("Vectors: %d  seconds/test: %d  TPS floor: %.1f",
            names.size(), perTest.get(), tpsFloor.get()));
        report.add("---");

        index = -1;
        info("Vector matrix: %d modules, %ds each.", names.size(), perTest.get());
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
            report.add(String.format("  %-32s SKIP", names.get(index)));
            startNext(); return;
        }
        if (!current.isActive()) current.toggle();
        minTps = 20.0;
        phase = Phase.RUN;
        phaseStart = System.currentTimeMillis();
        info("[%d/%d] %s", index + 1, names.size(), names.get(index));
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
                report.add(String.format("  %-32s %.1f TPS  %s", names.get(index), minTps, pass ? "PASS" : "FAIL"));
                phase = Phase.GAP; phaseStart = System.currentTimeMillis();
            }
        } else if (phase == Phase.GAP) {
            if (elapsed >= gapSecs.get() * 1000L) startNext();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (phase == Phase.RUN)
            report.add(String.format("  %-32s DISCONNECTED — FAIL", names.get(index)));
        if (isActive()) toggle();
    }

    private void finish() {
        phase = Phase.DONE;
        report.add("---");
        long pass = report.stream().filter(l -> l.contains("PASS")).count();
        long fail = report.stream().filter(l -> l.contains("FAIL")).count();
        long skip = report.stream().filter(l -> l.endsWith("SKIP")).count();
        report.add(String.format("PASS: %d  FAIL: %d  SKIP: %d  TOTAL: %d", pass, fail, skip, names.size()));
        info("Vector matrix done — PASS: %d  FAIL: %d  SKIP: %d", pass, fail, skip);
        toggle();
    }

    private void saveReport() {
        try {
            File dir = new File(mc.runDirectory, "config/acaudit/reports");
            dir.mkdirs();
            File file = new File(dir, "matrix_" + ts().replace(" ", "_").replace(":", "-") + ".txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
                for (String line : report) pw.println(line);
            }
            info("Matrix saved: %s", file.getAbsolutePath());
        } catch (IOException e) {
            error("Failed to save matrix: %s", e.getMessage());
        }
    }

    private static String ts() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
