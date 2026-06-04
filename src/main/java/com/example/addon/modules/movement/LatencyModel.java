package com.example.addon.modules.movement;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

/**
 * Shared realistic-latency generator (movement deep-coverage, axis 4).
 *
 * A flat artificial delay is trivially distinguishable from real lag: real
 * high-latency connections have a base RTT plus jitter, occasional spikes, and
 * slow drift over a session. The question this models is whether an AC grants
 * MORE leniency to latency that looks real than to a constant fake delay — i.e.
 * whether "make my lag look organic" buys a bigger tolerance window.
 *
 * Shared by PingSpoof (KeepAlive hold) and the keepalive/transaction-timing
 * desync modules, so they all speak the same latency shape.
 */
public final class LatencyModel {
    private final Setting<Boolean> realistic;
    private final Setting<Integer> baseMs;
    private final Setting<Integer> jitterMs;
    private final Setting<Integer> spikeChancePct;
    private final Setting<Integer> spikeMs;
    private final Setting<Integer> driftMsPerMin;

    public LatencyModel(SettingGroup g) {
        realistic = g.add(new BoolSetting.Builder()
            .name("realistic-latency")
            .description("Shape the delay like a real connection (base + jitter + spikes + drift) instead of a flat value. Tests whether the AC trusts organic-looking lag more.")
            .defaultValue(false).build()
        );
        baseMs = g.add(new IntSetting.Builder()
            .name("base-ms").description("Baseline added latency.")
            .defaultValue(200).range(0, 10000).sliderRange(0, 2000)
            .visible(realistic::get).build()
        );
        jitterMs = g.add(new IntSetting.Builder()
            .name("jitter-ms").description("Random ±ms per sample (organic variance).")
            .defaultValue(40).range(0, 1000).sliderRange(0, 300)
            .visible(realistic::get).build()
        );
        spikeChancePct = g.add(new IntSetting.Builder()
            .name("spike-chance-%").description("Per-sample chance of a latency spike.")
            .defaultValue(8).range(0, 100).sliderRange(0, 40)
            .visible(realistic::get).build()
        );
        spikeMs = g.add(new IntSetting.Builder()
            .name("spike-ms").description("Extra ms added when a spike fires.")
            .defaultValue(600).range(0, 8000).sliderRange(0, 3000)
            .visible(realistic::get).build()
        );
        driftMsPerMin = g.add(new IntSetting.Builder()
            .name("drift-ms-per-min").description("Slow degradation: ms added per minute of session.")
            .defaultValue(0).range(0, 5000).sliderRange(0, 1000)
            .visible(realistic::get).build()
        );
    }

    public boolean realistic() { return realistic.get(); }

    /**
     * Compute the delay for one sample.
     * @param fallbackMs delay to use when realistic mode is off (the module's own flat value)
     * @param elapsedMs  ms since the module activated (for drift)
     */
    public long nextDelayMs(long fallbackMs, long elapsedMs) {
        if (!realistic.get()) return fallbackMs;
        double d = baseMs.get();
        if (jitterMs.get() > 0) d += (Math.random() * 2 - 1) * jitterMs.get();
        if (spikeChancePct.get() > 0 && Math.random() * 100 < spikeChancePct.get()) d += spikeMs.get();
        if (driftMsPerMin.get() > 0) d += driftMsPerMin.get() * (elapsedMs / 60000.0);
        return Math.max(0, (long) d);
    }
}
