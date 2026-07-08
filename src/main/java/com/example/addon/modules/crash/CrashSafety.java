package com.example.addon.modules.crash;

import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * Shared crash-safety net (review sections 9 + 10) — the module-level protection a
 * first-time user needs. Composed into a crash module like {@link GracefulResponse}.
 *
 * <p>It provides three things the category was missing:
 *
 *   MID-RUN ABORT — reads {@link ServerHealthMonitor} every tick and, if TPS stays below
 *     {@code abort-tps} (default 15) for a short sustained window, DISABLES the owning
 *     module immediately. Previously nothing stopped a single module at max intensity
 *     from flooring a server mid-run; the runner only gated BETWEEN vectors.
 *
 *   PROBE vs STRESS — PROBE starts at a low intensity and ramps up until the first sign
 *     of degradation, then reports the threshold and holds; STRESS runs at the configured
 *     intensity. This unifies the ad-hoc ramp-mode that only a few modules had, and
 *     directly answers "find the threshold" vs "test maximum resilience".
 *
 *   INTENSITY CAP — a global clamp so no module exceeds a per-tick budget the user set.
 *
 * <p>Wiring in a module: construct with the settings group, call {@link #onActivate(Module)},
 * gate firing with {@link #keepFiring()} each tick, and wrap per-tick counts with
 * {@link #scale(int)}.
 */
public final class CrashSafety {
    public enum Mode {
        /** Ramp intensity up from minimum until degradation, then hold and report the threshold. */
        PROBE,
        /** Fire at the configured intensity (clamped by the cap). */
        STRESS
    }

    private final Setting<Mode> mode;
    private final Setting<Double> abortTps;
    private final Setting<Integer> abortTicks;
    private final Setting<Integer> intensityCap;
    private final Setting<Integer> probeStart;
    private final Setting<Integer> probeStep;

    private Module owner;
    private ServerHealthMonitor health;
    private int belowCount;
    private int probeLevel;
    private boolean aborted;
    private double abortedAtTps;
    private int thresholdAtLevel = -1;

    public CrashSafety(SettingGroup g) {
        mode = g.add(new EnumSetting.Builder<Mode>()
            .name("safety-mode")
            .description("PROBE = ramp up until the server degrades, then hold and report the threshold; STRESS = fire at the configured intensity.")
            .defaultValue(Mode.STRESS).build());
        abortTps = g.add(new DoubleSetting.Builder()
            .name("abort-below-tps")
            .description("Auto-disable this module if server TPS stays below this (0 = never abort). The mid-run safety net.")
            .defaultValue(15.0).range(0, 20).sliderRange(0, 20).build());
        abortTicks = g.add(new IntSetting.Builder()
            .name("abort-sustained-ticks")
            .description("TPS must stay below abort-below-tps for this many ticks before aborting (avoids a single spike stopping the run).")
            .defaultValue(20).range(1, 200).sliderRange(3, 100)
            .visible(() -> abortTps.get() > 0).build());
        intensityCap = g.add(new IntSetting.Builder()
            .name("intensity-cap")
            .description("Global clamp on this module's per-tick count (0 = no cap). A safe ceiling for first-time testing.")
            .defaultValue(0).min(0).sliderMax(500).build());
        probeStart = g.add(new IntSetting.Builder()
            .name("probe-start")
            .description("PROBE mode: starting per-tick intensity.")
            .defaultValue(1).min(1).sliderMax(100)
            .visible(() -> mode.get() == Mode.PROBE).build());
        probeStep = g.add(new IntSetting.Builder()
            .name("probe-step")
            .description("PROBE mode: intensity increase per tick until degradation.")
            .defaultValue(5).min(1).sliderMax(200)
            .visible(() -> mode.get() == Mode.PROBE).build());
    }

    public void onActivate(Module self) {
        owner = self;
        health = null;
        belowCount = 0;
        probeLevel = probeStart.get();
        aborted = false;
        abortedAtTps = -1;
        thresholdAtLevel = -1;
    }

    public Mode mode() { return mode.get(); }
    public boolean aborted() { return aborted; }

    /**
     * Call once per tick. Samples TPS, advances the PROBE ramp, and aborts the module if
     * the server degrades past the floor. Returns false when the module should stop firing
     * this tick (either aborted, or waiting).
     */
    public boolean keepFiring() {
        if (aborted) return false;
        if (health == null) health = Modules.get().get(ServerHealthMonitor.class);

        double tps = (health != null && health.isActive() && health.isWarm()) ? health.getTps() : 20.0;
        boolean warm = health != null && health.isActive() && health.isWarm();

        // PROBE: note the level at which degradation first appears, then hold there.
        if (mode.get() == Mode.PROBE) {
            if (warm && tps < 19.0 && thresholdAtLevel < 0) thresholdAtLevel = probeLevel;
            if (thresholdAtLevel < 0) probeLevel += probeStep.get();
        }

        // Mid-run abort net.
        if (abortTps.get() > 0 && warm) {
            if (tps < abortTps.get()) {
                if (++belowCount >= abortTicks.get()) {
                    aborted = true;
                    abortedAtTps = tps;
                    if (owner != null && owner.isActive()) {
                        owner.warning("CrashSafety: TPS held below %.1f for %d ticks — auto-disabling to protect the server.",
                            abortTps.get(), abortTicks.get());
                        owner.toggle();
                    }
                    return false;
                }
            } else {
                belowCount = 0;
            }
        }
        return true;
    }

    /** Clamp/scale a requested per-tick count by the cap and the PROBE ramp. */
    public int scale(int requested) {
        int n = requested;
        if (mode.get() == Mode.PROBE) n = Math.min(n, probeLevel);
        if (intensityCap.get() > 0) n = Math.min(n, intensityCap.get());
        return Math.max(0, n);
    }

    /** A short summary line for the module's deactivate report. */
    public String reportLine() {
        if (aborted) return String.format("  Safety: ABORTED at TPS %.1f (server degraded past the floor).", abortedAtTps);
        if (mode.get() == Mode.PROBE && thresholdAtLevel >= 0)
            return "  Safety[PROBE]: server first degraded at ~" + thresholdAtLevel + "/tick — that is your threshold.";
        if (mode.get() == Mode.PROBE)
            return "  Safety[PROBE]: reached " + probeLevel + "/tick with no degradation seen.";
        return "  Safety[STRESS]: ran at configured intensity (no auto-abort triggered).";
    }
}
