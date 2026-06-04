package com.example.addon.modules.movement;

import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared threshold-seeker (modern-AC deep-coverage instrument).
 *
 * A measurement-based audit guesses a magic number and asks "did it flag?". A
 * real audit finds the EXACT boundary the target tolerates and reports it, so the
 * server owner can compare it against the value a correct simulation would allow.
 * This helper drives one scalar (a speed, an acceleration, a Y-delta, an injected
 * latency, anything) upward in small steps and watches a caller-supplied outcome
 * counter (typically MovementObserver.setbackCount()). The first step that
 * provokes a new correction is the detected boundary; the seeker backs off one
 * step, locks, and reports the threshold it found.
 *
 * What a well-implemented AC should do (generic patch signal): the located
 * boundary should sit at — not above — the value a correct physics simulation
 * permits for the player's real state. A boundary noticeably looser than vanilla
 * physics is exactly the slack an exploit lives in, and is what the report flags.
 *
 * Composition: a module constructs one with a label + starting value, calls
 * value() to read the current magnitude to apply, and update() once per probe
 * with the current outcome count.
 */
public final class OffsetSeeker {
    private final String label;
    private final Setting<Double> start;
    private final Setting<Double> step;
    private final Setting<Integer> interval;

    private double current;
    private int timer;
    private int lastOutcome;
    private boolean found;
    private double threshold;

    public OffsetSeeker(SettingGroup g, String label, double startDefault, double stepDefault, int intervalDefault) {
        this.label = label;
        start = g.add(new DoubleSetting.Builder()
            .name("seek-start").description("Starting " + label + " — pick a value the target accepts cleanly.")
            .defaultValue(startDefault).range(0.0, 100.0).sliderRange(0.0, Math.max(1.0, startDefault * 4)).build()
        );
        step = g.add(new DoubleSetting.Builder()
            .name("seek-step").description("How much to raise " + label + " each interval while seeking the boundary.")
            .defaultValue(stepDefault).range(0.0001, 10.0).sliderRange(0.001, Math.max(0.1, stepDefault * 8)).build()
        );
        interval = g.add(new IntSetting.Builder()
            .name("seek-interval").description("Probes to hold each value before stepping up (lets the outcome window resolve).")
            .defaultValue(intervalDefault).range(1, 400).sliderRange(2, 80).build()
        );
    }

    public void onActivate() {
        current = start.get(); timer = 0; lastOutcome = 0; found = false; threshold = 0;
    }

    public double value() { return current; }
    public boolean found() { return found; }
    public double threshold() { return threshold; }

    /**
     * Call once per probe with the current cumulative outcome count (e.g. setbacks).
     * Steps the value up every interval until the count rises, then locks the
     * boundary one step below and reports it through the printer once.
     */
    public void update(int outcomeCount, Consumer<String> printer) {
        if (found) { lastOutcome = outcomeCount; return; }
        if (outcomeCount > lastOutcome) {
            found = true;
            threshold = Math.max(start.get(), current - step.get());
            if (printer != null) printer.accept(String.format(
                "Seek: %s boundary ≈ %.4f (provoked correction at %.4f, locked to %.4f).",
                label, current, current, threshold));
            current = threshold;
        } else if (++timer >= interval.get()) {
            timer = 0;
            current += step.get();
        }
        lastOutcome = outcomeCount;
    }

    public void report(Consumer<String> printer) {
        for (String l : reportLines()) printer.accept(l);
    }

    public List<String> reportLines() {
        List<String> out = new ArrayList<>();
        if (found) out.add(String.format("  Seek result: %s tolerated up to ≈ %.4f before correction.", label, threshold));
        else out.add(String.format("  Seek result: no correction up to %.4f — %s never bounded in this run.", current, label));
        return out;
    }
}
