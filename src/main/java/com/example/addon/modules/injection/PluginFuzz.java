package com.example.addon.modules.injection;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.AuditReport;
import com.example.addon.audit.Finding;
import com.example.addon.audit.Severity;
import com.example.addon.modules.crash.ServerHealthMonitor;
import com.example.addon.modules.injection.PluginLibrary.PluginCommand;
import com.example.addon.modules.injection.PluginLibrary.PluginEntry;
import com.example.addon.modules.injection.PluginLibrary.PluginPayload;
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
 * AUDIT: Plugin-Aware Fuzzer (targeted payloads for detected plugins)
 *
 * PLATFORM: Paper/Bukkit (specific plugins — LiteBans, AdvancedBan, BanManager, CMI,
 * AuctionHouse, QuickShop, LuckPerms, EssentialsX, …).
 *
 * A real threat actor with normal player permissions uses payloads tailored to the
 * exact plugins a server runs. This drives the curated {@link PluginLibrary}: for each
 * targeted plugin it walks that plugin's known commands and injection points, sends the
 * payloads that MATCH the plugin's storage model (SQLi only where SQL exists — FLATFILE
 * plugins like EssentialsX get format/length/special instead), and grades the result
 * with the shared {@link InjectionDetect} (error leak / time delay / TPS dip / kick).
 *
 * AUTO mode targets whatever {@code command-fingerprint} detected; otherwise name
 * plugins manually. Each finding names the plugin, command, argument, the evidence, the
 * expected-vulnerable signal, the disclosure URL and the fix — a ready disclosure report.
 *
 * HONESTY: client-side inference, not proof; payloads are targeted probes, not version-
 * verified. SAFETY: destructive (stacked) and time-based payloads are off by default and
 * the module refuses a non-local server unless you confirm you own it.
 */
public class PluginFuzz extends Module {
    public enum TypeFilter { ALL, SQLI, FORMAT, LENGTH, SPECIAL }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgReport = settings.createGroup("Report");

    private final Setting<Boolean> autoDetect = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-detect").description("Target the plugins command-fingerprint detected. Off = use the 'plugins' list.")
        .defaultValue(true).build());
    private final Setting<String> plugins = sgGeneral.add(new StringSetting.Builder()
        .name("plugins").description("Comma-separated plugin ids to target when auto-detect is off (e.g. litebans,cmi,essentialsx).")
        .defaultValue("litebans").visible(() -> !autoDetect.get()).build());
    private final Setting<TypeFilter> typeFilter = sgGeneral.add(new EnumSetting.Builder<TypeFilter>()
        .name("payload-type").description("Restrict to one payload class across all plugins.")
        .defaultValue(TypeFilter.ALL).build());
    private final Setting<String> testTarget = sgGeneral.add(new StringSetting.Builder()
        .name("test-target").description("Replaces the placeholder username 'TestUser123' in command templates. Use a throwaway account you control.")
        .defaultValue("TestUser123").build());
    private final Setting<Integer> windowTicks = sgGeneral.add(new IntSetting.Builder()
        .name("window-ticks").description("Ticks to observe after each send (exceed time-based delays).")
        .defaultValue(160).range(20, 600).sliderRange(60, 300).build());
    private final Setting<Integer> delayThresholdMs = sgGeneral.add(new IntSetting.Builder()
        .name("delay-threshold-ms").description("Flag a reply slower than this (no baseline is taken across heterogeneous commands).")
        .defaultValue(2500).range(500, 10000).sliderRange(1000, 6000).build());
    private final Setting<Double> tpsFloor = sgGeneral.add(new DoubleSetting.Builder()
        .name("tps-floor").description("Flag a payload that dips TPS below this.")
        .defaultValue(19.0).range(0, 20).sliderRange(10, 20).build());

    private final Setting<Boolean> allowDestructive = sgSafety.add(new BoolSetting.Builder()
        .name("allow-destructive").description("Include destructive (stacked/data-changing) payloads. These WILL alter your DB if vulnerable.")
        .defaultValue(false).build());
    private final Setting<Boolean> allowTimeBased = sgSafety.add(new BoolSetting.Builder()
        .name("allow-time-based").description("Include time-based payloads (SLEEP/WAITFOR) — they intentionally lag the server.")
        .defaultValue(false).build());
    private final Setting<Boolean> safetyChecks = sgSafety.add(new BoolSetting.Builder()
        .name("safety-checks").description("Refuse a non-local server unless ownership is confirmed.").defaultValue(true).build());
    private final Setting<Boolean> iOwnServer = sgSafety.add(new BoolSetting.Builder()
        .name("i-own-this-server").description("Confirm you are authorised to audit this server (required for non-local addresses).")
        .defaultValue(false).build());

    private final Setting<Boolean> saveFile = sgReport.add(new BoolSetting.Builder()
        .name("save-report").description("Write a responsible-disclosure report (text/markdown/json) to config/acaudit/reports/.")
        .defaultValue(true).build());
    private final Setting<Boolean> logEach = sgReport.add(new BoolSetting.Builder()
        .name("log-each").description("Log each payload and response as it happens.").defaultValue(true).build());

    private record Step(PluginEntry plugin, PluginCommand cmd, PluginPayload payload) {}
    private final List<Step> plan = new ArrayList<>();
    private AuditReport report;
    private ServerHealthMonitor health;
    private Module platformProbe;
    private boolean running;
    private int index;
    private long stepStart, firstReplyMs;
    private double minTps;
    private boolean kicked;
    private String kickReason, leak, leakEngine;
    private int findings;

    public PluginFuzz() {
        super(AddonTemplate.INJECTION_CATEGORY, "plugin-fuzz",
            "Drives the curated plugin-specific payload library against the plugins your server runs (auto-detected or named), matching payload type to each plugin's storage model. Own server only.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null || mc.player == null) { error("Join a server first."); toggle(); return; }
        String address = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address
            : (mc.isInSingleplayer() ? "singleplayer" : "unknown");
        if (safetyChecks.get() && !isLocal(address) && !iOwnServer.get()) {
            error("Remote server '%s'. Enable Safety > i-own-this-server to confirm authorisation.", address); toggle(); return;
        }
        health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && !health.isActive()) health.toggle();
        platformProbe = Modules.get().get("platform-probe");
        if (platformProbe != null && !platformProbe.isActive()) platformProbe.toggle();

        buildPlan();
        if (plan.isEmpty()) {
            error("No payloads. %s", autoDetect.get()
                ? "No detected plugins matched the library — run command-fingerprint first, or turn auto-detect off and name plugins."
                : "None of the named plugins are in the library (have: " + String.join(", ", PluginLibrary.ids()) + ").");
            toggle(); return;
        }

        report = new AuditReport("PluginFuzz").setServer(address);
        report.addNote("Targeted probes, client-side inference — confirm against the plugin's query logs. Fix: parameterised queries / prepared statements + input validation.");
        if (!allowDestructive.get()) report.addNote("Destructive payloads excluded (allow-destructive off).");
        if (!allowTimeBased.get()) report.addNote("Time-based payloads excluded (allow-time-based off).");
        findings = 0; index = -1; running = true;

        info("Plugin-fuzz: %d step(s) across %d plugin(s).", plan.size(),
            plan.stream().map(s -> s.plugin().id).distinct().count());
        startNext();
    }

    private void buildPlan() {
        plan.clear();
        List<PluginEntry> targets = new ArrayList<>();
        if (autoDetect.get()) targets.addAll(PluginLibrary.suggestedFromDetected());
        else for (String id : plugins.get().split(",")) {
            PluginEntry e = PluginLibrary.byId(id.trim());
            if (e == null) e = PluginLibrary.byNamespace(id.trim());
            if (e != null && !targets.contains(e)) targets.add(e);
        }
        boolean flatfileSkipped = false;
        for (PluginEntry pl : targets) {
            for (PluginCommand cmd : pl.commands) {
                for (PluginPayload pay : cmd.payloads) {
                    if (typeFilter.get() != TypeFilter.ALL && !pay.type.equalsIgnoreCase(typeFilter.get().name())) continue;
                    if (pay.destructive && !allowDestructive.get()) continue;
                    if (pay.delayMs > 0 && !allowTimeBased.get()) continue;
                    if ("SQLI".equalsIgnoreCase(pay.type) && "FLATFILE".equalsIgnoreCase(pl.storage)) { flatfileSkipped = true; continue; }
                    plan.add(new Step(pl, cmd, pay));
                }
            }
        }
        if (flatfileSkipped && report != null)
            report.addNote("Some SQLi payloads skipped: target plugin uses FLATFILE storage (no SQL surface).");
    }

    private void startNext() {
        index++;
        if (index >= plan.size()) { finish(); return; }
        Step s = plan.get(index);
        firstReplyMs = 0; minTps = 20.0; kicked = false; kickReason = null; leak = null; leakEngine = null;
        stepStart = System.currentTimeMillis();
        String cmd = s.cmd().template.replace("{x}", s.payload().text);
        if (!"TestUser123".equals(testTarget.get())) cmd = cmd.replace("TestUser123", testTarget.get());
        mc.player.networkHandler.sendChatCommand(cmd);
        if (logEach.get()) info("[%d/%d] %s :: /%s", index + 1, plan.size(), s.plugin().id, cmd);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!running) return;
        if (health != null && health.isWarm()) minTps = Math.min(minTps, health.getTps());
        if (System.currentTimeMillis() - stepStart < windowTicks.get() * 50L) return;
        report.add(classify(plan.get(index)));
        startNext();
    }

    private Finding classify(Step s) {
        long latency = firstReplyMs > 0 ? firstReplyMs - stepStart : -1;
        String name = s.plugin().id + ":" + s.cmd().arg + ":" + s.payload().type;
        Finding f = InjectionDetect.classify(name, "PLUGIN", s.payload().delayMs, s.payload().text, s.payload().note,
            firstReplyMs > 0, latency, minTps, -1, delayThresholdMs.get(), tpsFloor.get(), kicked, kickReason, leakEngine, leak);
        // plugin-specific disclosure context
        f.details().add("plugin: " + s.plugin().id + " (storage " + s.plugin().storage + ")");
        f.details().add("command: /" + s.cmd().template + "  [arg: " + s.cmd().arg + ", surface " + s.cmd().surface + "]");
        if (s.payload().vulnerableSignal != null && !s.payload().vulnerableSignal.isBlank())
            f.details().add("if vulnerable: " + s.payload().vulnerableSignal);
        if (s.plugin().disclosureUrl != null && !s.plugin().disclosureUrl.isBlank())
            f.details().add("disclosure: " + s.plugin().disclosureUrl);
        if (s.plugin().fixExample != null && !s.plugin().fixExample.isBlank())
            f.details().add("fix: " + s.plugin().fixExample);
        if (InjectionDetect.flagged(f)) findings++;
        return f;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!running) return;
        if (event.packet instanceof GameMessageS2CPacket m) {
            String text = m.content().getString();
            if (text.isBlank()) return;
            if (firstReplyMs == 0) firstReplyMs = System.currentTimeMillis();
            if (leak == null) {
                SqlErrorFingerprint.Hit h = SqlErrorFingerprint.scan(text);
                if (h.found()) { leak = h.match(); leakEngine = h.engine(); if (logEach.get()) warning("  DB error leaked (%s): %s", h.engine(), text); }
            }
        } else if (event.packet instanceof DisconnectS2CPacket d) {
            kicked = true; kickReason = d.reason().getString();
        }
    }

    private void finish() {
        running = false;
        if (platformProbe instanceof PlatformProbe pp) report.setPlatform(pp.getBrand(), pp.getPlatform());
        info("==== Plugin-fuzz complete: %d step(s), %d flagged ====", plan.size(), findings);
        for (String l : report.summaryLineDetail()) info("%s", l);
        if (saveFile.get()) {
            File json = report.save(mc.runDirectory, "pluginfuzz");
            if (json != null) info("Report: %s(.txt/.md/.json)", json.getAbsolutePath().replaceAll("\\.json$", ""));
        }
        if (isActive()) toggle();
    }

    private static boolean isLocal(String a) {
        if (a == null) return false;
        String s = a.toLowerCase();
        return s.contains("localhost") || s.startsWith("127.") || s.startsWith("192.168.")
            || s.startsWith("10.") || s.startsWith("172.") || s.endsWith(".lan") || s.equals("unknown") || s.equals("singleplayer");
    }

    @Override
    public void onDeactivate() { running = false; }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (running && index >= 0 && index < plan.size())
            report.add(Finding.of(plan.get(index).plugin().id + ":kick", "PLUGIN", Severity.MEDIUM, "KICK",
                "disconnected during this payload" + (kickReason != null ? " — " + kickReason : "")));
        if (isActive()) finish();
    }
}
