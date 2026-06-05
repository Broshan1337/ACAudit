package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.ConflictClasses;
import com.example.addon.audit.VectorObservation;
import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUDIT: Combo Orchestrator (N-vector simultaneous, staggered, baseline-aware)
 *
 * Real attackers do not probe one surface at a time. This runs THREE OR MORE named
 * modules at once, each with its own per-vector start offset (stagger), and grades
 * what the COMBINATION produced — TPS, setbacks, inventory resyncs, server messages,
 * item dupe and kicks observed across the whole window.
 *
 * NAMED LIBRARY: pick a curated combo (e.g. SpeedPlusInteractionFlood, TimerPlusBlink,
 * AuctionRacePlusSellRace, DupeUnderLag) instead of typing names — these are the
 * pairings known to surface combination-only failures.
 *
 * AUTO-BASELINE: with baseline on, it first runs each vector ALONE for a short window
 * and records its metrics, THEN runs the combination, THEN reports the combined result
 * against the per-vector baselines — so an effect that only appears together (a race
 * that only dupes under lag, a movement only flagged when compensation is inflated) is
 * called out automatically instead of left for you to eyeball.
 *
 * CONFLICTS: two velocity-writing vectors overwrite each other and two packet-holding
 * vectors interfere on the send pipeline (shared {@link ConflictClasses}); this warns,
 * suggests a substitute, and (optionally) runs anyway so you can see the degraded result.
 *
 * Run against your OWN local server only.
 */
public class ComboOrchestrator extends Module {
    public enum Preset {
        CUSTOM,
        SPEED_PLUS_INTERACTION_FLOOD,   // movement under inventory-event pressure
        TIMER_PLUS_BLINK,               // accelerated time + held packets
        AUCTION_RACE_PLUS_SELL_RACE,    // two economy races at once
        DUPE_UNDER_LAG,                 // dupe race + lag inducer widens the window
        ECON_FUZZ_UNDER_PRESSURE,       // economy parsing under event-queue pressure
        ANTISETBACK_PLUS_SPEED,         // dropped corrections + a flaggable move
        TRANSACTION_TIMING_PLUS_MOVE,   // inflated compensation + a flaggable move
        REACH_UNDER_LOW_TPS             // reach when the server is behind
    }

    private record Preset2(String vectors, String offsets) {}
    private static final Map<Preset, Preset2> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put(Preset.SPEED_PLUS_INTERACTION_FLOOD, new Preset2("speed,interaction-flood", "0,10"));
        PRESETS.put(Preset.TIMER_PLUS_BLINK,             new Preset2("ac-timer,ac-blink", "0,20"));
        PRESETS.put(Preset.AUCTION_RACE_PLUS_SELL_RACE,  new Preset2("auction-race,sell-race", "0,5"));
        PRESETS.put(Preset.DUPE_UNDER_LAG,               new Preset2("shift-click-race,packet-spammer", "0,0"));
        PRESETS.put(Preset.ECON_FUZZ_UNDER_PRESSURE,     new Preset2("econ-fuzz,interaction-flood", "0,5"));
        PRESETS.put(Preset.ANTISETBACK_PLUS_SPEED,       new Preset2("anti-setback,speed", "0,10"));
        PRESETS.put(Preset.TRANSACTION_TIMING_PLUS_MOVE, new Preset2("transaction-timing,phase", "0,10"));
        PRESETS.put(Preset.REACH_UNDER_LOW_TPS,          new Preset2("reach-world-state-race,movement-crash", "10,0"));
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Preset> preset = sgGeneral.add(new EnumSetting.Builder<Preset>()
        .name("preset").description("Pick a curated combination, or CUSTOM to type your own below.")
        .defaultValue(Preset.CUSTOM).build());
    private final Setting<String> vectors = sgGeneral.add(new StringSetting.Builder()
        .name("vectors").description("CUSTOM: comma-separated module names to run simultaneously (3+ supported).")
        .defaultValue("speed,shift-click-race,packet-spammer")
        .visible(() -> preset.get() == Preset.CUSTOM).build());
    private final Setting<String> offsets = sgGeneral.add(new StringSetting.Builder()
        .name("start-offsets").description("CUSTOM: per-vector start delay (ticks), aligned by index. Blank/short = 0.")
        .defaultValue("0,10,20")
        .visible(() -> preset.get() == Preset.CUSTOM).build());
    private final Setting<Boolean> autoBaseline = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-baseline").description("Run each vector ALONE first, then the combo, then report combined-vs-baseline.")
        .defaultValue(true).build());
    private final Setting<Integer> baselineSecs = sgGeneral.add(new IntSetting.Builder()
        .name("baseline-seconds").description("Window for each per-vector baseline run.")
        .defaultValue(8).range(2, 60).sliderRange(4, 30).visible(autoBaseline::get).build());
    private final Setting<Integer> durationSecs = sgGeneral.add(new IntSetting.Builder()
        .name("duration-seconds").description("How long to hold the combination after the last vector starts.")
        .defaultValue(15).range(2, 180).sliderRange(5, 90).build());
    private final Setting<Boolean> runOnConflict = sgGeneral.add(new BoolSetting.Builder()
        .name("run-on-conflict").description("Run anyway on a structural conflict (off = abort and just report it).")
        .defaultValue(true).build());

    private enum Phase { BASELINE, COMBO, DONE }
    private record Vec(String name, Module module, int offsetTicks) {}
    private record Base(String name, double minTps, int setbacks, int resyncs, int messages, boolean dup) {}

    private final List<Vec> plan = new ArrayList<>();
    private final List<Base> baselines = new ArrayList<>();
    private boolean[] startedFlags;
    private ServerHealthMonitor health;
    private Phase phase = Phase.DONE;
    private int baseIndex;
    private long phaseStart;
    private int tick;
    private VectorObservation obs = new VectorObservation();

    public ComboOrchestrator() {
        super(AddonTemplate.TESTING_CATEGORY, "combo-orchestrator",
            "Runs 3+ vectors simultaneously with per-vector stagger and a named combo library; auto-baselines each vector alone then grades what the COMBINATION uniquely produced.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) { error("Join a server first."); toggle(); return; }
        plan.clear(); baselines.clear();
        tick = 0;

        String vecStr, offStr;
        if (preset.get() != Preset.CUSTOM) {
            Preset2 p = PRESETS.get(preset.get());
            vecStr = p.vectors(); offStr = p.offsets();
            info("Preset %s: %s", preset.get(), vecStr);
        } else { vecStr = vectors.get(); offStr = offsets.get(); }

        String[] names = vecStr.split(",");
        String[] offs = offStr.split(",");
        List<String> nameList = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            String n = names[i].trim();
            if (n.isEmpty()) continue;
            Module m = Modules.get().get(n);
            if (m == null) { warning("Module '%s' not found — skipping.", n); continue; }
            int off = 0;
            if (i < offs.length) { try { off = Integer.parseInt(offs[i].trim()); } catch (NumberFormatException ignored) {} }
            plan.add(new Vec(n, m, off));
            nameList.add(n);
        }
        if (plan.isEmpty()) { error("No valid vectors."); toggle(); return; }

        ConflictClasses.Result r = ConflictClasses.check(nameList);
        if (r.conflict()) {
            for (String w : r.warnings()) warning("%s", w);
            if (!runOnConflict.get()) { info("Aborting (run-on-conflict off)."); toggle(); return; }
        }

        health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && !health.isActive()) health.toggle();

        startedFlags = new boolean[plan.size()];
        if (autoBaseline.get()) {
            phase = Phase.BASELINE; baseIndex = -1;
            info("Auto-baseline: %d vector(s) alone (%ds each), then the combo.", plan.size(), baselineSecs.get());
            startNextBaseline();
        } else {
            beginCombo();
        }
    }

    // ---- baseline phase ----

    private void startNextBaseline() {
        baseIndex++;
        if (baseIndex >= plan.size()) { beginCombo(); return; }
        obs = new VectorObservation();
        obs.snapshotPre(mc.player);
        Vec v = plan.get(baseIndex);
        if (!v.module().isActive()) v.module().toggle();
        phaseStart = System.currentTimeMillis();
        info("[baseline %d/%d] '%s' alone …", baseIndex + 1, plan.size(), v.name());
    }

    private void endBaseline() {
        Vec v = plan.get(baseIndex);
        if (v.module().isActive()) v.module().toggle();
        obs.snapshotPost(mc.player);
        baselines.add(new Base(v.name(), obs.minTps, obs.setbacks, obs.resyncs, obs.messages, obs.duplicated()));
        info("  baseline '%s': minTPS %.1f, %d setbacks, %d resyncs, %d msgs%s",
            v.name(), obs.minTps, obs.setbacks, obs.resyncs, obs.messages, obs.duplicated() ? ", DUP" : "");
    }

    // ---- combo phase ----

    private void beginCombo() {
        phase = Phase.COMBO;
        obs = new VectorObservation();
        obs.snapshotPre(mc.player);
        for (int i = 0; i < startedFlags.length; i++) startedFlags[i] = false;
        tick = 0;
        phaseStart = System.currentTimeMillis();
        int maxOff = plan.stream().mapToInt(Vec::offsetTicks).max().orElse(0);
        info("Combo: %d vectors, last starts +%dt, hold %ds.", plan.size(), maxOff, durationSecs.get());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (phase == Phase.DONE) return;
        if (health != null && health.isWarm()) obs.sampleTps(health.getTps());
        long elapsed = System.currentTimeMillis() - phaseStart;

        if (phase == Phase.BASELINE) {
            if (elapsed >= baselineSecs.get() * 1000L) { endBaseline(); startNextBaseline(); }
            return;
        }

        // COMBO
        tick++;
        for (int i = 0; i < plan.size(); i++) {
            if (!startedFlags[i] && tick >= plan.get(i).offsetTicks()) {
                Vec v = plan.get(i);
                if (!v.module().isActive()) v.module().toggle();
                startedFlags[i] = true;
                info("+%dt started '%s'.", tick, v.name());
            }
        }
        int maxOff = plan.stream().mapToInt(Vec::offsetTicks).max().orElse(0);
        if (elapsed - maxOff * 50L >= durationSecs.get() * 1000L) finishCombo();
    }

    private void finishCombo() {
        obs.snapshotPost(mc.player);
        info("==== Combo result ====");
        info("  combined: minTPS %.1f, %d setbacks, %d resyncs, %d msgs%s",
            obs.minTps, obs.setbacks, obs.resyncs, obs.messages, obs.duplicated() ? ", DUPLICATED" : "");

        if (!baselines.isEmpty()) {
            double worstBaseTps = baselines.stream().mapToDouble(Base::minTps).min().orElse(20.0);
            int maxBaseSet = baselines.stream().mapToInt(Base::setbacks).max().orElse(0);
            int maxBaseRes = baselines.stream().mapToInt(Base::resyncs).max().orElse(0);
            int maxBaseMsg = baselines.stream().mapToInt(Base::messages).max().orElse(0);
            boolean baseDup = baselines.stream().anyMatch(Base::dup);

            List<String> elevated = new ArrayList<>();
            if (obs.minTps + 1.0 < worstBaseTps) elevated.add(String.format("TPS lower (%.1f vs %.1f alone)", obs.minTps, worstBaseTps));
            if (obs.setbacks > maxBaseSet) elevated.add("more setbacks (" + obs.setbacks + " vs " + maxBaseSet + ")");
            if (obs.resyncs > maxBaseRes) elevated.add("more resyncs (" + obs.resyncs + " vs " + maxBaseRes + ")");
            if (obs.messages > maxBaseMsg) elevated.add("more server msgs (" + obs.messages + " vs " + maxBaseMsg + ")");
            if (obs.duplicated() && !baseDup) elevated.add("DUPLICATION only under combination");

            if (elevated.isEmpty())
                info("  vs baseline: no combination-only effect — combo ~= the individual vectors.");
            else {
                warning("  COMBINATION-ONLY EFFECT: %s", String.join("; ", elevated));
                warning("  -> this is the value of multi-vector testing; correlate with AC/economy/server logs.");
            }
        } else {
            info("  (enable auto-baseline to auto-compare against each vector alone)");
        }
        toggle();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (phase != Phase.DONE) obs.onPacket(event.packet);
    }

    @Override
    public void onDeactivate() {
        for (Vec v : plan) if (v.module() != null && v.module().isActive()) v.module().toggle();
        phase = Phase.DONE;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        warning("Disconnected during combo — the %s took the server/connection down.",
            phase == Phase.BASELINE ? "baseline" : "combination");
        if (isActive()) toggle();
    }
}
