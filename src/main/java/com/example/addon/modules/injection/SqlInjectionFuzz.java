package com.example.addon.modules.injection;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.AuditReport;
import com.example.addon.audit.Finding;
import com.example.addon.audit.Severity;
import com.example.addon.modules.crash.ServerHealthMonitor;
import com.example.addon.modules.injection.InjectionPayloads.Category;
import com.example.addon.modules.injection.InjectionPayloads.Payload;
import com.example.addon.modules.testing.PlatformProbe;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: SQL Injection Fuzzer
 *
 * PLATFORM: Paper/Bukkit (plugins backing economy/data/shops/ranks/punishments with
 * SQL — MySQL, MariaDB, SQLite, Postgres, MSSQL; MongoDB via the NoSQL set).
 *
 * Pushes SQL-injection payloads through a command's argument and infers, from the
 * client side, whether the plugin built a query out of that argument unsafely:
 *
 *   - ERROR LEAK   — the plugin echoed a DB driver/syntax error (near-proof; CRITICAL).
 *   - TIME DELAY   — a SLEEP/WAITFOR/BENCHMARK payload made the reply arrive ~Ns late.
 *   - TPS DROP     — a heavy/synchronous query dipped server TPS (ServerHealthMonitor).
 *   - KICK/SILENCE — an unusual kick, or a command that normally replies going silent.
 *
 * HONESTY: a client cannot see the database or the query — these are CANDIDATES to
 * confirm against the plugin's own query logs, not proof (a leaked error is the
 * closest to proof). Time/TPS signals are confounded by async DB pools; absence of a
 * signal is NOT a guarantee of safety. The report says so on every finding.
 *
 * SAFETY: destructive stacked payloads (DROP/DELETE/UPDATE/INSERT/TRUNCATE) are
 * excluded unless allow-destructive is on, and the module refuses a non-local server
 * unless you confirm you own it. Run on YOUR server only, to get it patched.
 *
 * Set {command} to a real command with {x} at the injection point, e.g.
 * "balance {x}", "pay Notch {x}", "team description {x}". Fix for everything here:
 * parameterised queries / prepared statements + input validation.
 */
public class SqlInjectionFuzz extends Module {
    public enum ScanMode { QUICK, FULL, CATEGORY, CUSTOM }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgReport = settings.createGroup("Report");

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("Command with {x} at the injection point (no leading slash). E.g. 'balance {x}', 'pay Notch {x}'.")
        .defaultValue("balance {x}").build());
    private final Setting<ScanMode> scanMode = sgGeneral.add(new EnumSetting.Builder<ScanMode>()
        .name("scan-mode").description("QUICK = representative subset; FULL = whole library; CATEGORY = one class; CUSTOM = one payload you type.")
        .defaultValue(ScanMode.QUICK).build());
    private final Setting<Category> category = sgGeneral.add(new EnumSetting.Builder<Category>()
        .name("category").description("Which payload class (CATEGORY mode).")
        .defaultValue(Category.CLASSIC).visible(() -> scanMode.get() == ScanMode.CATEGORY).build());
    private final Setting<String> customPayload = sgGeneral.add(new StringSetting.Builder()
        .name("custom-payload").description("A single payload to test (CUSTOM mode).")
        .defaultValue("' OR 1=1-- ").visible(() -> scanMode.get() == ScanMode.CUSTOM).build());
    private final Setting<String> benignValue = sgGeneral.add(new StringSetting.Builder()
        .name("benign-value").description("A normal value for {x} used to calibrate the baseline response time.")
        .defaultValue("1").build());
    private final Setting<Integer> windowTicks = sgGeneral.add(new IntSetting.Builder()
        .name("window-ticks").description("Ticks to observe after each send (must exceed time-based delays; 5s SLEEP = 100t).")
        .defaultValue(160).range(20, 600).sliderRange(60, 300).build());
    private final Setting<Integer> baselineSamples = sgGeneral.add(new IntSetting.Builder()
        .name("baseline-samples").description("Benign sends used to measure normal response time before fuzzing.")
        .defaultValue(5).range(0, 20).sliderRange(0, 10).build());
    private final Setting<Integer> delayThresholdMs = sgGeneral.add(new IntSetting.Builder()
        .name("delay-threshold-ms").description("Flag a reply slower than baseline + this many ms.")
        .defaultValue(1500).range(200, 10000).sliderRange(500, 5000).build());
    private final Setting<Double> tpsFloor = sgGeneral.add(new DoubleSetting.Builder()
        .name("tps-floor").description("Flag a payload that dips TPS below this.")
        .defaultValue(19.0).range(0, 20).sliderRange(10, 20).build());

    private final Setting<Boolean> allowDestructive = sgSafety.add(new BoolSetting.Builder()
        .name("allow-destructive").description("Include stacked DROP/DELETE/UPDATE/INSERT/TRUNCATE payloads. These WILL damage your own DB if the plugin is vulnerable.")
        .defaultValue(false).build());
    private final Setting<Boolean> safetyChecks = sgSafety.add(new BoolSetting.Builder()
        .name("safety-checks").description("Refuse a non-local server unless ownership is confirmed.")
        .defaultValue(true).build());
    private final Setting<Boolean> iOwnServer = sgSafety.add(new BoolSetting.Builder()
        .name("i-own-this-server").description("Confirm you are authorised to audit this server (required for non-local addresses).")
        .defaultValue(false).build());

    private final Setting<Boolean> saveFile = sgReport.add(new BoolSetting.Builder()
        .name("save-report").description("Write a responsible-disclosure report (text/markdown/json) to config/acaudit/reports/.")
        .defaultValue(true).build());
    private final Setting<Boolean> showPayloads = sgReport.add(new BoolSetting.Builder()
        .name("log-each").description("Log each payload and the response as it happens.")
        .defaultValue(true).build());

    private enum Phase { CALIBRATE, FUZZ, DONE }
    private final List<Payload> plan = new ArrayList<>();
    private final List<Long> calibLatencies = new ArrayList<>();
    private AuditReport report;
    private ServerHealthMonitor health;
    private Module platformProbe;
    private Phase phase = Phase.DONE;
    private int index;
    private long stepStart, firstReplyMs;
    private double minTps;
    private boolean kicked;
    private String kickReason, leak, leakEngine;
    private long baselineMs = -1;
    private int findings;

    public SqlInjectionFuzz() {
        super(AddonTemplate.INJECTION_CATEGORY, "sql-injection-fuzz",
            "Fuzzes a command's argument with SQL-injection payloads and infers execution from leaked DB errors, time delays, TPS dips and kicks. Writes a responsible-disclosure report. Own server only.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null || mc.player == null) { error("Join a server first."); toggle(); return; }
        String address = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address
            : (mc.isInSingleplayer() ? "singleplayer" : "unknown");
        if (safetyChecks.get() && !isLocal(address) && !iOwnServer.get()) {
            error("Remote server '%s'. Enable Safety > i-own-this-server to confirm authorisation.", address);
            toggle(); return;
        }
        if (!command.get().contains("{x}"))
            warning("No {x} marker in command — the payload will be appended to the end.");

        health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && !health.isActive()) health.toggle();
        platformProbe = Modules.get().get("platform-probe");
        if (platformProbe != null && !platformProbe.isActive()) platformProbe.toggle();

        buildPlan();
        if (plan.isEmpty()) { error("No payloads selected."); toggle(); return; }

        report = new AuditReport("SQLi:" + scanMode.get()).setServer(address);
        report.addNote("Findings are client-side inference — confirm against the plugin's query logs. Fix: parameterised queries / prepared statements + input validation.");
        if (!allowDestructive.get()) report.addNote("Destructive (stacked) payloads excluded (allow-destructive off).");
        calibLatencies.clear(); baselineMs = -1; findings = 0;

        info("SQLi fuzz [%s]: %d payload(s) via '%s'. Calibrating %d baseline sample(s)…",
            scanMode.get(), plan.size(), command.get(), baselineSamples.get());
        index = -1;
        if (baselineSamples.get() > 0) { phase = Phase.CALIBRATE; index = 0; startStep(benign()); }
        else { baselineMs = -1; phase = Phase.FUZZ; startNextPayload(); }
    }

    private void buildPlan() {
        plan.clear();
        switch (scanMode.get()) {
            case QUICK -> plan.addAll(InjectionPayloads.select(Category.QUICK, allowDestructive.get()));
            case FULL  -> plan.addAll(InjectionPayloads.select(Category.ALL, allowDestructive.get()));
            case CATEGORY -> plan.addAll(InjectionPayloads.select(category.get(), allowDestructive.get()));
            case CUSTOM -> plan.add(Payload.of(Category.CLASSIC, customPayload.get()));
        }
    }

    private String benign() { return benignValue.get(); }

    private void startStep(String value) {
        firstReplyMs = 0; minTps = 20.0; kicked = false; kickReason = null; leak = null; leakEngine = null;
        stepStart = System.currentTimeMillis();
        String cmd = command.get().contains("{x}") ? command.get().replace("{x}", value) : command.get() + " " + value;
        mc.player.networkHandler.sendChatCommand(cmd);
    }

    private void startNextPayload() {
        index++;
        if (index >= plan.size()) { finish(); return; }
        Payload p = plan.get(index);
        startStep(p.text());
        if (showPayloads.get()) info("[%d/%d] %s%s", index + 1, plan.size(), p.text(),
            p.delayMs() > 0 ? " (expects ~" + p.delayMs() + "ms)" : "");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (phase == Phase.DONE) return;
        if (health != null && health.isWarm()) minTps = Math.min(minTps, health.getTps());
        if (System.currentTimeMillis() - stepStart < windowTicks.get() * 50L) return;

        if (phase == Phase.CALIBRATE) {
            if (firstReplyMs > 0) calibLatencies.add(firstReplyMs - stepStart);
            index++;
            if (index < baselineSamples.get()) startStep(benign());
            else { finishCalibration(); phase = Phase.FUZZ; index = -1; startNextPayload(); }
        } else { // FUZZ
            report.add(classify(plan.get(index)));
            startNextPayload();
        }
    }

    private void finishCalibration() {
        if (calibLatencies.isEmpty()) {
            baselineMs = -1;
            report.addNote("Baseline: command produced no chat reply — time/silence detection limited (TPS + error-leak still active).");
            info("Baseline: no replies (time-based detection limited).");
        } else {
            long sum = 0; for (long l : calibLatencies) sum += l;
            baselineMs = sum / calibLatencies.size();
            info("Baseline reply ~%dms over %d sample(s).", baselineMs, calibLatencies.size());
        }
    }

    private Finding classify(Payload p) {
        long latency = firstReplyMs > 0 ? firstReplyMs - stepStart : -1;
        Finding f = InjectionDetect.classify(shortName(p), "SQLi", p.delayMs(), p.text(), p.note(),
            firstReplyMs > 0, latency, minTps, baselineMs, delayThresholdMs.get(), tpsFloor.get(),
            kicked, kickReason, leakEngine, leak);
        if (InjectionDetect.flagged(f)) findings++;
        return f;
    }

    private static String shortName(Payload p) {
        String t = p.text().replaceAll("\\s+", " ").trim();
        return p.category() + ":" + (t.length() > 24 ? t.substring(0, 24) + "…" : t);
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (phase == Phase.DONE) return;
        if (event.packet instanceof GameMessageS2CPacket m) {
            String text = m.content().getString();
            if (text.isBlank()) return;
            if (firstReplyMs == 0) firstReplyMs = System.currentTimeMillis();
            if (leak == null && phase == Phase.FUZZ) {
                SqlErrorFingerprint.Hit h = SqlErrorFingerprint.scan(text);
                if (h.found()) {
                    leak = h.match(); leakEngine = h.engine();
                    if (showPayloads.get()) warning("  DB error leaked (%s): %s", h.engine(), text);
                }
            }
        } else if (event.packet instanceof DisconnectS2CPacket d) {
            kicked = true; kickReason = d.reason().getString();
        }
    }

    private void finish() {
        phase = Phase.DONE;
        if (platformProbe instanceof PlatformProbe pp) report.setPlatform(pp.getBrand(), pp.getPlatform());
        info("==== SQLi fuzz complete: %d payload(s), %d flagged ====", plan.size(), findings);
        for (String l : report.summaryLineDetail()) info("%s", l);
        if (saveFile.get()) {
            File json = report.save(mc.runDirectory, "sqli");
            if (json != null) info("Report: %s(.txt/.md/.json)", json.getAbsolutePath().replaceAll("\\.json$", ""));
        }
        if (isActive()) toggle();
    }

    private static boolean isLocal(String a) {
        if (a == null) return false;
        String s = a.toLowerCase();
        return s.contains("localhost") || s.startsWith("127.") || s.startsWith("192.168.")
            || s.startsWith("10.") || s.startsWith("172.") || s.endsWith(".lan") || s.equals("unknown")
            || s.equals("singleplayer");
    }

    @Override
    public void onDeactivate() { phase = Phase.DONE; }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (phase != Phase.DONE && index >= 0 && index < plan.size())
            report.add(Finding.of(shortName(plan.get(index)), "SQLi", Severity.MEDIUM, "KICK",
                "disconnected during this payload" + (kickReason != null ? " — " + kickReason : "")));
        if (isActive()) finish();
    }
}
