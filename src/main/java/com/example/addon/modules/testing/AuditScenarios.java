package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.AuditReport;
import com.example.addon.audit.Finding;
import com.example.addon.audit.Severity;
import com.example.addon.audit.VectorObservation;
import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AUDIT: Audit Scenarios (named Paper-environment playbooks)
 *
 * End-to-end, opinionated test cases that chain the right vectors with the right
 * observation for a specific question — the things a server owner actually wants a
 * one-click answer to. Each writes a severity-classified {@link AuditReport}
 * (text/markdown/json) with platform context, like auto-audit-runner.
 *
 *   ECON_INTEGRITY    — read balance, run the economy/dupe vectors, read balance
 *                       again, report the NET change (and flag a non-finite or
 *                       absurd jump as a confirmed economy break).
 *   AC_COVERAGE_MAP   — run each movement vector and map DETECTED / CORRECTED /
 *                       UNDETECTED from the AC's chat/action-bar/title/kick signals,
 *                       giving a coverage map for THIS anti-cheat.
 *   STABILITY_BASELINE— run the crash set at short, recovery-gated windows and record
 *                       min TPS + recovery time per vector — a stability profile of
 *                       this server to compare against future runs.
 *   PLUGIN_FINGERPRINT— drive the fingerprinting probes (platform / command /
 *                       plugin-message / packet-limiter) and capture the platform
 *                       context that frames every other finding.
 *   COMBO_MATRIX      — baseline each vector in a set alone, then run every PAIR and
 *                       flag the pairs whose outcome differs from either alone
 *                       (synergistic attack surfaces).
 *
 * Run against your OWN local server only.
 */
public class AuditScenarios extends Module {
    public enum Scenario { ECON_INTEGRITY, AC_COVERAGE_MAP, STABILITY_BASELINE, PLUGIN_FINGERPRINT, COMBO_MATRIX }
    private enum Kind { VECTOR, COMBO, BALANCE }
    private enum Phase { RUN, GAP, DONE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Scenario> scenario = sgGeneral.add(new EnumSetting.Builder<Scenario>()
        .name("scenario").description("Which named playbook to run.").defaultValue(Scenario.ECON_INTEGRITY).build());
    private final Setting<Integer> perStep = sgGeneral.add(new IntSetting.Builder()
        .name("seconds-per-step").defaultValue(10).range(2, 120).sliderRange(4, 60).build());
    private final Setting<Integer> maxGap = sgGeneral.add(new IntSetting.Builder()
        .name("max-gap-seconds").defaultValue(6).range(0, 60).sliderRange(0, 20).build());
    private final Setting<Double> tpsFloor = sgGeneral.add(new DoubleSetting.Builder()
        .name("tps-floor").defaultValue(19.0).range(0, 20).sliderRange(10, 20).build());
    private final Setting<Double> recoverTo = sgGeneral.add(new DoubleSetting.Builder()
        .name("recover-to-tps").defaultValue(19.0).range(0, 20).sliderRange(10, 20).build());
    private final Setting<Boolean> saveFile = sgGeneral.add(new BoolSetting.Builder()
        .name("save-report").description("Write text + markdown + json to config/acaudit/reports/.").defaultValue(true).build());

    // econ
    private final Setting<String> balanceCmd = sgGeneral.add(new StringSetting.Builder()
        .name("balance-command").description("ECON: command (no slash) that prints your balance.").defaultValue("balance")
        .visible(() -> scenario.get() == Scenario.ECON_INTEGRITY).build());
    private final Setting<String> econVectors = sgGeneral.add(new StringSetting.Builder()
        .name("econ-vectors").description("ECON: vectors to run between the two balance reads.").defaultValue("econ-fuzz,sell-race,vault-value-probe")
        .visible(() -> scenario.get() == Scenario.ECON_INTEGRITY).build());
    // coverage
    private final Setting<String> movementSet = sgGeneral.add(new StringSetting.Builder()
        .name("movement-set").description("COVERAGE: movement vectors to map.").defaultValue("speed,phase,ac-no-fall,offset-boundary,bhop,ac-timer,ac-high-jump,omni-sprint")
        .visible(() -> scenario.get() == Scenario.AC_COVERAGE_MAP).build());
    // stability
    private final Setting<String> crashSet = sgGeneral.add(new StringSetting.Builder()
        .name("crash-set").description("STABILITY: crash vectors to profile (kept light).").defaultValue("packet-spammer,movement-crash,arm-animation-flood,metadata-flood,entity-spam,chunk-border-stress")
        .visible(() -> scenario.get() == Scenario.STABILITY_BASELINE).build());
    // matrix
    private final Setting<String> matrixSet = sgGeneral.add(new StringSetting.Builder()
        .name("matrix-set").description("MATRIX: vectors to cross — every pair is run after baselining each alone.").defaultValue("speed,shift-click-race,packet-spammer,movement-crash")
        .visible(() -> scenario.get() == Scenario.COMBO_MATRIX).build());

    private record Task(String label, List<String> names, String category, int windowSecs, Kind kind) {}
    private final List<Task> tasks = new ArrayList<>();
    private final Map<String, double[]> baseMetrics = new HashMap<>(); // name -> {minTps,setbacks,resyncs,messages,dup}
    private final VectorObservation obs = new VectorObservation();
    private AuditReport report;
    private ServerHealthMonitor health;
    private Module platformProbe;
    private Phase phase = Phase.DONE;
    private int index;
    private long phaseStart;
    private boolean capturingBalance;
    private double balanceBefore = Double.NaN, balanceAfter = Double.NaN;
    private boolean sawNonFiniteBalance;

    private static final Pattern NUM = Pattern.compile("[-+]?\\d[\\d,]*(?:\\.\\d+)?");

    public AuditScenarios() {
        super(AddonTemplate.TESTING_CATEGORY, "audit-scenarios",
            "Named Paper-environment playbooks (econ-integrity, AC-coverage-map, stability-baseline, plugin-fingerprint, combo-matrix) that each emit a severity-classified report.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null || mc.player == null) { error("Join a server first."); toggle(); return; }
        health = Modules.get().get(ServerHealthMonitor.class);
        platformProbe = Modules.get().get("platform-probe");
        if (health != null && !health.isActive()) health.toggle();
        if (platformProbe != null && !platformProbe.isActive()) platformProbe.toggle();

        tasks.clear(); baseMetrics.clear();
        balanceBefore = balanceAfter = Double.NaN; sawNonFiniteBalance = false;
        report = new AuditReport("SCENARIO:" + scenario.get())
            .setServer(mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address
                : (mc.isInSingleplayer() ? "singleplayer" : "unknown"));

        buildTasks();
        if (tasks.isEmpty()) { error("Nothing to run for %s.", scenario.get()); toggle(); return; }

        index = -1;
        info("Scenario %s: %d step(s).", scenario.get(), tasks.size());
        startNext();
    }

    private void buildTasks() {
        switch (scenario.get()) {
            case ECON_INTEGRITY -> {
                tasks.add(new Task("balance-before", List.of(), "ECON", 3, Kind.BALANCE));
                for (String n : split(econVectors.get())) tasks.add(new Task(n, List.of(n), "ECON", perStep.get(), Kind.VECTOR));
                tasks.add(new Task("balance-after", List.of(), "ECON", 3, Kind.BALANCE));
            }
            case AC_COVERAGE_MAP -> {
                for (String n : split(movementSet.get())) tasks.add(new Task(n, List.of(n), "MOVEMENT", perStep.get(), Kind.VECTOR));
            }
            case STABILITY_BASELINE -> {
                for (String n : split(crashSet.get())) tasks.add(new Task(n, List.of(n), "CRASH", Math.max(4, perStep.get() / 2), Kind.VECTOR));
            }
            case PLUGIN_FINGERPRINT -> {
                for (String n : List.of("platform-probe", "command-fingerprint", "plugin-message-probe", "packet-limiter-map"))
                    if (Modules.get().get(n) != null) tasks.add(new Task(n, List.of(n), "PLATFORM", perStep.get(), Kind.VECTOR));
            }
            case COMBO_MATRIX -> {
                List<String> set = split(matrixSet.get());
                for (String n : set) tasks.add(new Task(n + " (baseline)", List.of(n), "CRASH", Math.max(4, perStep.get() / 2), Kind.VECTOR));
                for (int i = 0; i < set.size(); i++)
                    for (int j = i + 1; j < set.size(); j++)
                        tasks.add(new Task(set.get(i) + " + " + set.get(j), List.of(set.get(i), set.get(j)), "COMBO", perStep.get(), Kind.COMBO));
            }
        }
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split(",")) { String x = t.trim(); if (!x.isEmpty()) out.add(x); }
        return out;
    }

    private void startNext() {
        index++;
        if (index >= tasks.size()) { finish(); return; }
        Task t = tasks.get(index);
        obs.reset(); obs.snapshotPre(mc.player);
        capturingBalance = t.kind() == Kind.BALANCE;
        if (t.kind() == Kind.BALANCE) {
            mc.player.networkHandler.sendChatCommand(balanceCmd.get());
            info("[%d/%d] /%s …", index + 1, tasks.size(), balanceCmd.get());
        } else {
            for (String n : t.names()) { Module m = Modules.get().get(n); if (m != null && !m.isActive()) m.toggle(); }
            info("[%d/%d] %s …", index + 1, tasks.size(), t.label());
        }
        phase = Phase.RUN; phaseStart = System.currentTimeMillis();
    }

    private void stopCurrent() {
        if (index < 0 || index >= tasks.size()) return;
        for (String n : tasks.get(index).names()) { Module m = Modules.get().get(n); if (m != null && m.isActive()) m.toggle(); }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (phase == Phase.DONE) return;
        if (health != null && health.isWarm()) obs.sampleTps(health.getTps());
        long elapsed = System.currentTimeMillis() - phaseStart;

        if (phase == Phase.RUN) {
            if (health != null && health.msSinceLastSample() > 6000) obs.hang = true;
            if (elapsed >= tasks.get(index).windowSecs() * 1000L) { endTask(); }
        } else if (phase == Phase.GAP) {
            boolean recovered = health == null || !health.isWarm() || health.getTps() >= recoverTo.get();
            if (recovered || elapsed >= maxGap.get() * 1000L) startNext();
        }
    }

    private void endTask() {
        Task t = tasks.get(index);
        capturingBalance = false;
        if (t.kind() == Kind.BALANCE) {
            recordBalance(t.label());   // balance captured via onReceive into lastBalance
        } else {
            stopCurrent();
            obs.snapshotPost(mc.player);
            if (t.kind() == Kind.COMBO) report.add(classifyCombo(t));
            else {
                Finding f = obs.classify(t.label(), t.category(), tpsFloor.get());
                report.add(f);
                logFinding(f);
                if (scenario.get() == Scenario.COMBO_MATRIX) // store single baselines
                    baseMetrics.put(t.names().get(0), new double[]{obs.minTps, obs.setbacks, obs.resyncs, obs.messages, obs.duplicated() ? 1 : 0});
            }
        }
        phase = Phase.GAP; phaseStart = System.currentTimeMillis();
    }

    private Finding classifyCombo(Task t) {
        List<String> det = new ArrayList<>();
        det.add(String.format("min TPS %.1f, %d setbacks, %d resyncs, %d msgs", obs.minTps, obs.setbacks, obs.resyncs, obs.messages));
        if (obs.disconnected) return new Finding(t.label(), "COMBO", Severity.CRITICAL, "DISCONNECTED", det);
        double[] a = baseMetrics.get(t.names().get(0)), b = baseMetrics.get(t.names().get(1));
        List<String> elevated = new ArrayList<>();
        if (a != null && b != null) {
            double worstTps = Math.min(a[0], b[0]);
            if (obs.minTps + 1.0 < worstTps) elevated.add(String.format("TPS %.1f vs %.1f alone", obs.minTps, worstTps));
            if (obs.setbacks > Math.max(a[1], b[1])) elevated.add("setbacks up");
            if (obs.resyncs > Math.max(a[2], b[2])) elevated.add("resyncs up");
            if (obs.messages > Math.max(a[3], b[3])) elevated.add("msgs up");
            if (obs.duplicated() && a[4] == 0 && b[4] == 0) elevated.add("DUP only combined");
        }
        if (obs.duplicated()) { det.add(0, "duplication under combination"); return new Finding(t.label(), "COMBO", Severity.CRITICAL, "DUPLICATED", det); }
        if (!elevated.isEmpty()) { det.add("combination-only: " + String.join(", ", elevated)); return new Finding(t.label(), "COMBO", Severity.HIGH, "SYNERGY", det); }
        if (obs.minTps < tpsFloor.get()) return new Finding(t.label(), "COMBO", Severity.MEDIUM, "DEGRADED", det);
        return new Finding(t.label(), "COMBO", Severity.INFO, "no-synergy", det);
    }

    private void recordBalance(String which) {
        boolean before = which.contains("before");
        if (sawNonFiniteBalance) {
            report.add(Finding.of("balance", "ECON", Severity.CRITICAL, "NON-FINITE",
                "balance command returned a non-finite/garbage value — economy break"));
            if (before) balanceBefore = 0; else balanceAfter = 0;
            return;
        }
        double v = lastBalance;
        if (before) balanceBefore = v; else balanceAfter = v;
        report.add(Finding.of("balance", "ECON", Severity.INFO, before ? "BEFORE" : "AFTER",
            Double.isNaN(v) ? "could not parse balance from chat — check balance-command" : String.format("%.2f", v)));
        info("  %s balance: %s", before ? "before" : "after", Double.isNaN(v) ? "unparsed" : String.format("%.2f", v));
    }

    private double lastBalance = Double.NaN;

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (phase != Phase.RUN) return;
        obs.onPacket(event.packet);
        if (capturingBalance && event.packet instanceof GameMessageS2CPacket m) {
            String text = m.content().getString();
            String low = text.toLowerCase();
            if (low.contains("infinity") || low.contains("nan")) { sawNonFiniteBalance = true; return; }
            Matcher mt = NUM.matcher(text);
            while (mt.find()) {
                try {
                    double d = Double.parseDouble(mt.group().replace(",", ""));
                    if (Double.isNaN(lastBalance) || Math.abs(d) > Math.abs(lastBalance)) lastBalance = d;
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void finish() {
        phase = Phase.DONE;
        if (scenario.get() == Scenario.ECON_INTEGRITY) summariseEcon();
        if (platformProbe instanceof PlatformProbe pp) report.setPlatform(pp.getBrand(), pp.getPlatform());
        if (saveFile.get()) {
            File json = report.save(mc.runDirectory, "scenario");
            if (json != null) info("Report saved: %s(.txt/.md/.json)", json.getAbsolutePath().replaceAll("\\.json$", ""));
        }
        for (String l : report.summaryLineDetail()) info("%s", l);
        info("==== Scenario %s complete ====", scenario.get());
        if (isActive()) toggle();
    }

    private void summariseEcon() {
        if (Double.isNaN(balanceBefore) || Double.isNaN(balanceAfter)) {
            report.addNote("Economy net change not computed (a balance read failed to parse).");
            return;
        }
        double net = balanceAfter - balanceBefore;
        List<String> det = new ArrayList<>(List.of(
            String.format("before %.2f -> after %.2f (net %+.2f)", balanceBefore, balanceAfter, net)));
        Severity sev;
        String verdict;
        if (!Double.isFinite(net) || Math.abs(net) > 1e12) { sev = Severity.CRITICAL; verdict = "BALANCE-BREAK"; det.add("non-finite or absurd jump — confirmed economy break"); }
        else if (net > 0) { sev = Severity.MEDIUM; verdict = "BALANCE-UP"; det.add("balance increased — verify it matches items actually sold/given; an unbacked increase is a dupe"); }
        else { sev = Severity.INFO; verdict = "BALANCE-OK"; det.add("no unexplained increase"); }
        report.add(new Finding("econ-integrity", "ECON", sev, verdict, det));
    }

    private void logFinding(Finding f) {
        String msg = String.format("    %s %s — %s", f.severity().tag(), f.verdict(),
            f.details().isEmpty() ? "" : String.join("; ", f.details()));
        if (f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH) warning("%s", msg); else info("%s", msg);
    }

    @Override
    public void onDeactivate() { stopCurrent(); phase = Phase.DONE; }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (phase != Phase.DONE && index >= 0 && index < tasks.size())
            report.add(Finding.of(tasks.get(index).label(), tasks.get(index).category(), Severity.CRITICAL, "DISCONNECTED",
                "disconnected during this step" + (obs.disconnectReason != null ? " — " + obs.disconnectReason : "")));
        if (isActive()) { finish(); }
    }
}
