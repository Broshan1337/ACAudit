package com.example.addon.modules.antidupe;

import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

/**
 * Shared precise-timing trigger (review axis 1: timing precision over volume).
 *
 * Modern plugins rate-limit obvious spam, so the interesting dupes happen when
 * ONE packet lands at exactly the right tick — the single-tick TOCTOU window
 * between "marked sold" and "removed", or between an output slot recomputing and
 * the grid decrementing. Instead of bursting, the module detects an anchor event
 * (container open, hopper transfer phase, transaction start) and calls arm();
 * this helper then fires exactly once, `offset` ticks later.
 *
 *   SNIPE — fire once at the configured offset after every anchor.
 *   SWEEP — across repeated anchors, walk the offset 0..max one tick at a time,
 *           so DupeObserver reveals WHICH tick (if any) is exploitable, rather
 *           than the operator guessing it.
 */
public final class TickWindow {
    public enum Mode { SNIPE, SWEEP }

    private final Setting<Mode> mode;
    private final Setting<Integer> offset;
    private final Setting<Integer> sweepMax;

    private int countdown = -1;   // ticks until fire (-1 = idle)
    private int sweepOffset = 0;
    private int lastFiredOffset = -1;

    public TickWindow(SettingGroup g) {
        mode = g.add(new EnumSetting.Builder<Mode>()
            .name("timing")
            .description("SNIPE = fire once at a fixed offset after the anchor; SWEEP = walk the offset 0..max across attempts to locate the vulnerable tick.")
            .defaultValue(Mode.SNIPE).build()
        );
        offset = g.add(new IntSetting.Builder()
            .name("snipe-offset-ticks")
            .description("Ticks after the anchor to fire (SNIPE).")
            .defaultValue(0).range(0, 40).sliderRange(0, 20)
            .visible(() -> mode.get() == Mode.SNIPE).build()
        );
        sweepMax = g.add(new IntSetting.Builder()
            .name("sweep-max-offset")
            .description("Highest offset to try before wrapping back to 0 (SWEEP).")
            .defaultValue(10).range(1, 40).sliderRange(1, 20)
            .visible(() -> mode.get() == Mode.SWEEP).build()
        );
    }

    public Mode mode() { return mode.get(); }
    public int lastFiredOffset() { return lastFiredOffset; }

    public void onActivate() { countdown = -1; sweepOffset = 0; lastFiredOffset = -1; }

    /** Anchor detected — schedule the single shot. */
    public void arm() {
        countdown = (mode.get() == Mode.SWEEP) ? sweepOffset : offset.get();
    }

    public boolean armed() { return countdown >= 0; }

    /** Call once per tick. Returns true on the exact firing tick. */
    public boolean shouldFire() {
        if (countdown < 0) return false;
        if (countdown == 0) {
            lastFiredOffset = (mode.get() == Mode.SWEEP) ? sweepOffset : offset.get();
            countdown = -1;
            if (mode.get() == Mode.SWEEP) sweepOffset = (sweepOffset + 1) % (sweepMax.get() + 1);
            return true;
        }
        countdown--;
        return false;
    }
}
