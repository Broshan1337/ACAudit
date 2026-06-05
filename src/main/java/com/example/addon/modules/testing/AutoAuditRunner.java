package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.AuditReport;
import com.example.addon.audit.ConflictClasses;
import com.example.addon.audit.Finding;
import com.example.addon.audit.Severity;
import com.example.addon.audit.VectorObservation;
import com.example.addon.modules.crash.ServerHealthMonitor;
import com.example.addon.packet.SequenceStore;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: Auto-Audit Runner (the pipeline)
 *
 * Turns the individual vectors into one end-to-end audit a server owner or AC/
 * plugin developer can run and read. It:
 *
 *   - runs a recommended ORDER (platform-probe + health-monitor up first and kept
 *     on, then movement, then dupe, then CRASH last);
 *   - GRADES each vector from the client-visible packet stream (setbacks, inventory
 *     resyncs, item dupe/loss delta, AC detection signals via chat/action-bar/title/
 *     kick, min TPS, server hang) and assigns a SEVERITY — not just TPS PASS/FAIL;
 *   - writes a timestamped report in THREE formats (text / markdown / json) with the
 *     platform context embedded, ready to attach to a GitHub issue or Discord report;
 *   - can DIFF against the previous run (regression detection after a plugin/AC update);
 *   - has a DRY-RUN (print the plan, fire nothing), per-category SKIP toggles, and a
 *     pre-run SAFETY GATE (refuses a non-local server unless you confirm you own it);
 *   - runs PARALLEL groups (CUSTOM targets joined with '+' co-run, conflict-checked);
 *   - applies CRASH SAFETY: recovery-gated gaps (wait for TPS to recover before the
 *     next vector), recovery-time measurement, crash/lag/hang classification, and a
 *     SAFE-MODE that shortens windows and excludes the hardest crashers;
 *   - on a kick: records the reason, and (optionally) REJOINS, waits for the server
 *     to stabilise, and RESUMES at the next vector instead of aborting the whole run.
 *
 * Modes: GUIDED (recommended first audit) · QUICK (<5 min representative subset) ·
 * CUSTOM (your ordered list; '+' joins simultaneous vectors).
 *
 * Run against your OWN local server only.
 */
public class AutoAuditRunner extends Module {
    public enum Mode { GUIDED, QUICK, CUSTOM }
    private enum Phase { RUN, GAP, REJOIN_WAIT, DONE }

    // Recommended guided sequence — quiet probes first, crash last (category derived live).
    private static final String[] GUIDED = {
        "speed", "phase", "ac-no-fall", "offset-boundary",
        "shift-click-race", "slot-sync-probe", "event-order-probe", "econ-fuzz",
        "packet-spammer", "movement-crash", "entity-spam", "channel-flood", "nbt-bomb"
    };
    // Representative <5-min subset across categories; lighter crash vectors only.
    private static final String[] QUICK = {
        "speed", "phase", "shift-click-race", "econ-fuzz", "packet-spammer", "movement-crash"
    };
    // The hardest crashers — excluded by safe-mode.
    private static final List<String> SAFE_EXCLUDE = List.of(
        "nbt-bomb", "channel-flood", "snbt-depth", "structure-string-flood", "payload-flood",
        "passenger-loop", "entity-spam", "book-crash");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgReport = settings.createGroup("Report");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("GUIDED = recommended first audit; QUICK = <5-min subset; CUSTOM = your ordered list ('+' joins simultaneous vectors).")
        .defaultValue(Mode.GUIDED).build());
    private final Setting<String> targets = sgGeneral.add(new StringSetting.Builder()
        .name("targets").description("CUSTOM: comma-separated steps in order. Join names with '+' to run simultaneously. Use 'seq:<name>' to replay a saved sequence, or 'seqtag:<tag>' to replay every saved sequence with that tag (regression testing).")
        .defaultValue("speed, phase, shift-click-race+packet-spammer, nbt-bomb")
        .visible(() -> mode.get() == Mode.CUSTOM).build());
    private final Setting<Integer> perTest = sgGeneral.add(new IntSetting.Builder()
        .name("seconds-per-test").defaultValue(10).range(2, 120).sliderRange(5, 60).build());
    private final Setting<Integer> maxGap = sgGeneral.add(new IntSetting.Builder()
        .name("max-gap-seconds").description("Upper bound on the recovery gap between vectors.")
        .defaultValue(8).range(0, 60).sliderRange(0, 30).build());
    private final Setting<Boolean> dryRun = sgGeneral.add(new BoolSetting.Builder()
        .name("dry-run").description("Print/save the plan and fire NOTHING. Review the test plan before committing.")
        .defaultValue(false).build());

    private final Setting<Boolean> skipMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-movement").description("GUIDED/QUICK: skip movement vectors.").defaultValue(false).build());
    private final Setting<Boolean> skipDupe = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-dupe").description("GUIDED/QUICK: skip dupe vectors.").defaultValue(false).build());
    private final Setting<Boolean> skipCrash = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-crash").description("GUIDED/QUICK: skip crash vectors (use on a production-like server).").defaultValue(false).build());

    // Safety
    private final Setting<Boolean> safetyChecks = sgSafety.add(new BoolSetting.Builder()
        .name("safety-checks").description("Run the pre-flight checklist and refuse a non-local server unless you confirm ownership.")
        .defaultValue(true).build());
    private final Setting<Boolean> iOwnServer = sgSafety.add(new BoolSetting.Builder()
        .name("i-own-this-server").description("Confirm you are authorised to audit this server (required for non-local addresses).")
        .defaultValue(false).build());
    private final Setting<Boolean> safeMode = sgSafety.add(new BoolSetting.Builder()
        .name("safe-mode").description("Measurement-grade: shorter windows, recovery-gated gaps, and exclude the hardest crashers (nbt-bomb, channel-flood, ...).")
        .defaultValue(false).build());
    private final Setting<Double> recoverTo = sgSafety.add(new DoubleSetting.Builder()
        .name("recover-to-tps").description("Wait in the gap until TPS recovers to this before the next vector.")
        .defaultValue(19.0).range(0, 20).sliderRange(10, 20).build());
    private final Setting<Boolean> autoRejoin = sgSafety.add(new BoolSetting.Builder()
        .name("auto-rejoin").description("On a kick: rejoin, wait for the server to stabilise, and resume at the next vector instead of aborting.")
        .defaultValue(true).build());
    private final Setting<Integer> rejoinTries = sgSafety.add(new IntSetting.Builder()
        .name("max-rejoins").description("Give up after this many reconnects in one run.")
        .defaultValue(3).range(0, 10).sliderRange(0, 5).visible(autoRejoin::get).build());
    private final Setting<Integer> stabilizeSecs = sgSafety.add(new IntSetting.Builder()
        .name("stabilize-seconds").description("After rejoining, wait at least this long (and for TPS recovery) before resuming.")
        .defaultValue(8).range(1, 60).sliderRange(3, 30).visible(autoRejoin::get).build());

    // Report
    private final Setting<Double> tpsFloor = sgReport.add(new DoubleSetting.Builder()
        .name("tps-floor").description("Crash PASS threshold.").defaultValue(19.0).range(0, 20).sliderRange(10, 20).build());
    private final Setting<Boolean> compareToLast = sgReport.add(new BoolSetting.Builder()
        .name("compare-to-last").description("Diff this run against the previous saved report (regression detection).")
        .defaultValue(true).build());
    private final Setting<Boolean> saveFile = sgReport.add(new BoolSetting.Builder()
        .name("save-report").description("Write text + markdown + json to config/acaudit/reports/.")
        .defaultValue(true).build());

    private record Step(List<String> names) {}
    private final List<Step> plan = new ArrayList<>();
    private final VectorObservation obs = new VectorObservation();
    private AuditReport report;
    private ServerHealthMonitor health;
    private Module platformProbe;
    private Phase phase = Phase.DONE;
    private int index;
    private long phaseStart;
    private Finding lastFinding;
    private boolean lastDegraded;
    private ServerInfo serverInfo;
    private int rejoinsUsed;
    private long rejoinedAt;

    public AutoAuditRunner() {
        super(AddonTemplate.TESTING_CATEGORY, "auto-audit-runner",
            "End-to-end audit pipeline: graded, severity-classified findings in text/markdown/json with regression diff, crash safety and auto-rejoin. The heart of ACAudit.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null || mc.player == null) { error("Join a server first."); toggle(); return; }
        health = Modules.get().get(ServerHealthMonitor.class);
        platformProbe = Modules.get().get("platform-probe");
        serverInfo = mc.getCurrentServerEntry();
        rejoinsUsed = 0; lastFinding = null; lastDegraded = false;

        String address = serverInfo != null ? serverInfo.address : (mc.isInSingleplayer() ? "singleplayer" : "unknown");
        if (!preflight(address)) { toggle(); return; }

        buildPlan();
        if (plan.isEmpty()) { error("No vectors in plan (everything skipped?)."); toggle(); return; }

        report = new AuditReport(mode.get() + (safeMode.get() ? "+safe" : "")).setServer(address);
        for (String w : conflictNotes()) report.addNote(w);

        if (dryRun.get()) { doDryRun(); return; }

        // Keep context probes on for the whole audit.
        if (health != null && !health.isActive()) health.toggle();
        if (platformProbe != null && !platformProbe.isActive()) platformProbe.toggle();

        index = -1;
        info("Auto-audit [%s%s]: %d step(s), %ds each. Report: %s.",
            mode.get(), safeMode.get() ? "+safe" : "", plan.size(), windowSecs(), saveFile.get() ? "on" : "off");
        startNext();
    }

    // ---- planning ----

    private void buildPlan() {
        plan.clear();
        if (mode.get() == Mode.CUSTOM) {
            for (String stepTok : targets.get().split(",")) {
                String tok = stepTok.trim();
                if (tok.startsWith("seqtag:")) {                       // expand a tag into one step per matching sequence
                    String tag = tok.substring(7).trim();
                    for (SequenceStore.SavedSequence sq : SequenceStore.list())
                        if (sq.tags != null && sq.tags.stream().anyMatch(t -> t.equalsIgnoreCase(tag)))
                            plan.add(new Step(List.of("seq:" + sq.name)));
                    continue;
                }
                List<String> names = new ArrayList<>();
                for (String n : tok.split("\\+")) { String t = n.trim(); if (!t.isEmpty()) names.add(t); }
                if (!names.isEmpty()) plan.add(new Step(names));
            }
        } else {
            String[] base = mode.get() == Mode.QUICK ? QUICK : GUIDED;
            for (String n : base) {
                if (safeMode.get() && SAFE_EXCLUDE.contains(n)) continue;
                String cat = categoryOf(n);
                if (skipMovement.get() && cat.equals("MOVEMENT")) continue;
                if (skipDupe.get() && cat.equals("DUPE")) continue;
                if (skipCrash.get() && cat.equals("CRASH")) continue;
                plan.add(new Step(List.of(n)));
            }
        }
    }

    private String categoryOf(String name) {
        Module m = Modules.get().get(name);
        if (m == null) return "CRASH";
        if (m.category == AddonTemplate.MOVEMENT_CATEGORY) return "MOVEMENT";
        if (m.category == AddonTemplate.DUPE_CATEGORY)
            return (name.contains("econ") || name.contains("vault") || name.contains("sign")) ? "ECON" : "DUPE";
        return "CRASH"; // crash + testing load modules graded on TPS
    }

    private String stepCategory(Step s) {
        if (s.names().size() == 1 && s.names().get(0).startsWith("seq:")) return "COMBO";
        if (s.names().size() > 1) return "COMBO";
        return categoryOf(s.names().get(0));
    }

    private List<String> conflictNotes() {
        List<String> out = new ArrayList<>();
        for (Step s : plan) {
            if (s.names().size() < 2) continue;
            ConflictClasses.Result r = ConflictClasses.check(s.names());
            if (r.conflict()) out.addAll(r.warnings());
        }
        return out;
    }

    private int windowSecs() { return safeMode.get() ? Math.max(3, perTest.get() / 2) : perTest.get(); }

    // ---- safety ----

    private boolean preflight(String address) {
        if (!safetyChecks.get()) return true;
        boolean local = mc.isInSingleplayer() || isLocal(address);
        info("Pre-flight: server=%s  %s", address, local ? "LOCAL ✓" : "REMOTE");
        if (!local && !iOwnServer.get()) {
            error("Remote server '%s'. Enable Safety > i-own-this-server to confirm you are authorised, or connect to a local test server.", address);
            return false;
        }
        info("Pre-flight: health-monitor %s, platform-probe %s.",
            health != null ? "available" : "MISSING", platformProbe != null ? "available" : "MISSING");
        if (mode.get() != Mode.CUSTOM && !skipCrash.get() && !safeMode.get())
            warning("Plan includes CRASH vectors at full intensity — use safe-mode or skip-crash on anything you can't afford to drop.");
        return true;
    }

    private static boolean isLocal(String a) {
        if (a == null) return false;
        String s = a.toLowerCase();
        return s.contains("localhost") || s.startsWith("127.") || s.startsWith("192.168.")
            || s.startsWith("10.") || s.startsWith("172.") || s.endsWith(".lan") || s.equals("unknown");
    }

    private void doDryRun() {
        info("==== DRY RUN — plan only, nothing fired ====");
        int i = 1;
        for (Step s : plan) {
            String cat = stepCategory(s);
            String label = String.join(" + ", s.names());
            report.add(new Finding(label, cat, Severity.INFO, "PLANNED",
                List.of(cat + " · " + windowSecs() + "s window" + (s.names().size() > 1 ? " · simultaneous" : ""))));
            info("  [%d] %-40s %s", i++, label, cat);
        }
        report.addNote("DRY RUN: no packets sent.");
        finishReport();
        toggle();
    }

    // ---- execution ----

    private void startNext() {
        index++;
        if (index >= plan.size()) { finish(); return; }
        Step s = plan.get(index);
        // Saved-sequence step: load + replay through packet-inspector instead of toggling a module.
        if (s.names().size() == 1 && s.names().get(0).startsWith("seq:")) {
            String seqName = s.names().get(0).substring(4);
            SequenceStore.SavedSequence sq = SequenceStore.load(seqName);
            if (sq == null) { report.add(new Finding("seq:" + seqName, "COMBO", Severity.LOW, "SKIP", List.of("sequence not found"))); startNext(); return; }
            PacketInspector pi = Modules.get().get(PacketInspector.class);
            if (pi == null) { report.add(new Finding("seq:" + seqName, "COMBO", Severity.LOW, "SKIP", List.of("packet-inspector module missing"))); startNext(); return; }
            if (!pi.isActive()) pi.toggle();
            SequenceStore.LoadResult r = SequenceStore.toPackets(sq);
            pi.replaySaved(r.items(), 1.0);
            if (r.versionMismatch())
                report.addNote("seq '" + seqName + "' captured on " + r.savedVersion() + " — replay fields may differ on this version.");
            obs.reset(); obs.snapshotPre(mc.player);
            phase = Phase.RUN; phaseStart = System.currentTimeMillis();
            info("[%d/%d] seq:%s (%d pkts: %d wire/%d reflective/%d skipped) …",
                index + 1, plan.size(), seqName, r.items().size(), r.wireUsed(), r.reflectiveUsed(), r.skipped());
            return;
        }
        for (String n : s.names()) {
            Module m = Modules.get().get(n);
            if (m == null) {
                report.add(new Finding(n, "INFO", Severity.LOW, "SKIP", List.of("module not found")));
                continue;
            }
            if (!m.isActive()) m.toggle();
        }
        obs.reset();
        obs.snapshotPre(mc.player);
        phase = Phase.RUN;
        phaseStart = System.currentTimeMillis();
        info("[%d/%d] %s …", index + 1, plan.size(), String.join(" + ", s.names()));
    }

    private void stopStep() {
        if (index < 0 || index >= plan.size()) return;
        for (String n : plan.get(index).names()) {
            if (n.startsWith("seq:")) continue;   // sequence replays are fire-and-forget, no module to stop
            Module m = Modules.get().get(n);
            if (m != null && m.isActive()) m.toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (phase == Phase.DONE) return;

        if (phase == Phase.REJOIN_WAIT) {
            if (mc.world == null || mc.player == null) return;             // still reconnecting
            if (rejoinedAt == 0) return;
            boolean settled = System.currentTimeMillis() - rejoinedAt >= stabilizeSecs.get() * 1000L
                && health != null && health.isWarm() && health.getTps() >= recoverTo.get();
            if (settled) { info("Stabilised — resuming."); startNext(); }
            return;
        }

        if (health != null && health.isWarm()) obs.sampleTps(health.getTps());
        long elapsed = System.currentTimeMillis() - phaseStart;

        if (phase == Phase.RUN) {
            // Hang detection: connected but the server stopped sending time updates.
            if (health != null && health.msSinceLastSample() > 6000) obs.hang = true;
            if (elapsed >= windowSecs() * 1000L) {
                stopStep();
                Step s = plan.get(index);
                obs.snapshotPost(mc.player);
                lastFinding = obs.classify(String.join(" + ", s.names()), stepCategory(s), tpsFloor.get());
                report.add(lastFinding);
                lastDegraded = obs.minTps < recoverTo.get();
                logFinding(lastFinding);
                phase = Phase.GAP; phaseStart = System.currentTimeMillis();
            }
        } else if (phase == Phase.GAP) {
            boolean recovered = health == null || !health.isWarm() || health.getTps() >= recoverTo.get();
            if (recovered && lastDegraded && lastFinding != null) {
                lastFinding.details().add(String.format("recovered in %.1fs", elapsed / 1000.0));
                lastDegraded = false;
            }
            if (recovered || elapsed >= maxGap.get() * 1000L) {
                if (!recovered) warning("TPS still %.1f after %ds — continuing anyway.",
                    health != null ? health.getTps() : -1, maxGap.get());
                startNext();
            }
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (phase == Phase.RUN) obs.onPacket(event.packet);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (phase == Phase.DONE) return;
        if (phase == Phase.RUN && index >= 0 && index < plan.size()) {
            Step s = plan.get(index);
            List<String> det = new ArrayList<>();
            det.add("disconnected during this vector");
            if (obs.disconnectReason != null && !obs.disconnectReason.isBlank())
                det.add("reason: \"" + obs.disconnectReason + "\"");
            lastFinding = new Finding(String.join(" + ", s.names()), stepCategory(s),
                Severity.CRITICAL, "DISCONNECTED", det);
            report.add(lastFinding);
            warning("Disconnected during '%s'%s.", String.join(" + ", s.names()),
                obs.disconnectReason != null ? " — " + obs.disconnectReason : "");
        }
        if (autoRejoin.get() && serverInfo != null && rejoinsUsed < rejoinTries.get()) {
            rejoinsUsed++;
            phase = Phase.REJOIN_WAIT; rejoinedAt = 0;
            info("Auto-rejoin %d/%d to %s …", rejoinsUsed, rejoinTries.get(), serverInfo.address);
            reconnect();
            // stay active across the reconnect
        } else {
            if (autoRejoin.get() && serverInfo == null)
                report.addNote("Auto-rejoin unavailable (no server entry — singleplayer/LAN). Run aborted at the kick.");
            finish();
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (phase == Phase.REJOIN_WAIT) {
            rejoinedAt = System.currentTimeMillis();
            if (health != null && !health.isActive()) health.toggle();
            if (platformProbe != null && !platformProbe.isActive()) platformProbe.toggle();
            info("Rejoined — waiting %ds for stabilisation.", stabilizeSecs.get());
        }
    }

    private void reconnect() {
        final ServerInfo info = serverInfo;
        mc.execute(() -> {
            try {
                ConnectScreen.connect(new TitleScreen(), mc, ServerAddress.parse(info.address), info, false, null);
            } catch (Throwable t) {
                error("Reconnect failed: %s", t.getMessage());
                if (report != null) report.addNote("Reconnect failed: " + t.getMessage());
                if (phase != Phase.DONE) finish();
            }
        });
    }

    private void finish() {
        phase = Phase.DONE;
        finishReport();
        info("==== Auto-Audit complete: %s ====", report != null ? report.summaryLine() : "(no report)");
        if (isActive()) toggle();
    }

    private void finishReport() {
        if (report == null) return;
        if (platformProbe instanceof PlatformProbe pp)
            report.setPlatform(pp.getBrand(), pp.getPlatform());
        if (compareToLast.get()) {
            File prev = AuditReport.latestJson(mc.runDirectory, null);
            if (prev != null) for (String l : report.diffAgainst(prev)) report.addNote(l);
        }
        if (saveFile.get()) {
            File json = report.save(mc.runDirectory, "audit");
            if (json != null) info("Report saved: %s(.txt/.md/.json)",
                json.getAbsolutePath().replaceAll("\\.json$", ""));
            else error("Failed to save report.");
        }
        for (String l : report.summaryLineDetail()) info("%s", l);
    }

    private void logFinding(Finding f) {
        String msg = String.format("    %s %s — %s", f.severity().tag(), f.verdict(),
            f.details().isEmpty() ? "" : String.join("; ", f.details()));
        if (f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH) warning("%s", msg);
        else info("%s", msg);
    }

    @Override
    public void onDeactivate() {
        stopStep();
        if (platformProbe != null && platformProbe.isActive() && phase != Phase.DONE) platformProbe.toggle();
        phase = Phase.DONE;
    }
}
