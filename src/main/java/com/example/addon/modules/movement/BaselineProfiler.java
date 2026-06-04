package com.example.addon.modules.movement;

import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared behavioral-baseline helper (modern-AC deep-coverage, profile/heuristic class).
 *
 * A whole class of anticheats (heuristic/statistical ones — as opposed to the
 * deterministic physics-simulation class) builds a per-player behavioral profile
 * and flags DEVIATIONS from it rather than absolute illegal values. The audit
 * question for that class is: once the AC has learned a player's pattern, can a
 * cheat be introduced as a smooth CONTINUATION of the established baseline rather
 * than a sudden deviation, slipping under the deviation threshold?
 *
 * This helper sequences exactly that test. It runs a clean baseline window (the
 * module behaves normally), then ramps a delta in gradually over a second window,
 * then holds it. A module reads rampFraction() to scale whatever delta it applies.
 *
 * IMPORTANT (honest scoping): this probes the PROFILE/heuristic AC class. Against a
 * deterministic physics-simulation AC, a smooth ramp does not help — each tick is
 * still validated against absolute physics regardless of history. Modules built on
 * this helper say so, so a server owner reads the result against the right
 * architecture.
 *
 * What a well-implemented profiling AC should do (generic patch signal): anchor
 * the behavioral baseline to physically legal bounds, not purely to the player's
 * own recent history, so that a slow drift cannot relocate the "normal" envelope
 * into illegal territory. Deviation thresholds must be measured against ground
 * truth, not against a baseline the player can move.
 *
 * Composition: module calls onActivate / tick and scales its delta by rampFraction().
 */
public final class BaselineProfiler {
    private final Setting<Integer> baselineSecs;
    private final Setting<Integer> rampSecs;

    private long activatedAt;
    private int phaseLogged = -1; // 0 baseline, 1 ramping, 2 full

    public BaselineProfiler(SettingGroup g) {
        baselineSecs = g.add(new IntSetting.Builder()
            .name("baseline-seconds")
            .description("Behave cleanly this long first, letting a profiling AC learn the baseline before any delta is introduced.")
            .defaultValue(30).range(0, 1800).sliderRange(0, 300).build()
        );
        rampSecs = g.add(new IntSetting.Builder()
            .name("ramp-seconds")
            .description("After the baseline, drift the delta in gradually over this long (0 = introduce it all at once).")
            .defaultValue(30).range(0, 1800).sliderRange(0, 300).build()
        );
    }

    public void onActivate() { activatedAt = System.currentTimeMillis(); phaseLogged = -1; }

    private double elapsed() { return (System.currentTimeMillis() - activatedAt) / 1000.0; }

    public boolean inBaseline() { return elapsed() < baselineSecs.get(); }
    public boolean rampingIn() { double e = elapsed() - baselineSecs.get(); return e >= 0 && e < rampSecs.get(); }

    /** 0 during baseline, smoothly 0→1 across the ramp window, 1 afterwards. */
    public double rampFraction() {
        double e = elapsed() - baselineSecs.get();
        if (e <= 0) return 0.0;
        if (rampSecs.get() <= 0 || e >= rampSecs.get()) return 1.0;
        return e / rampSecs.get();
    }

    /** Call each tick; emits a one-line phase-transition note through the printer. */
    public void tick(Consumer<String> printer) {
        int phase = inBaseline() ? 0 : (rampingIn() ? 1 : 2);
        if (phase != phaseLogged && printer != null) {
            phaseLogged = phase;
            switch (phase) {
                case 0 -> printer.accept("Baseline: behaving cleanly so a profiling AC learns the pattern.");
                case 1 -> printer.accept("Ramp: drifting the delta in as a continuation of the established baseline.");
                case 2 -> printer.accept("Full delta reached — deviation introduced without a sudden step.");
            }
        }
    }

    public void report(Consumer<String> printer) {
        for (String l : reportLines()) printer.accept(l);
    }

    public List<String> reportLines() {
        List<String> out = new ArrayList<>();
        out.add(String.format("  Profile run: %ds baseline + %ds ramp; reached %.0f%% delta.",
            baselineSecs.get(), rampSecs.get(), rampFraction() * 100));
        out.add("  → probes the PROFILE/heuristic AC class; a deterministic physics AC validates each tick regardless of ramp.");
        return out;
    }
}
