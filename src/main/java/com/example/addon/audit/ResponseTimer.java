package com.example.addon.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures the time from a fired client action to the next server-visible response.
 *
 * <p>Coarse <b>by design</b>: the server does not echo a click id, so we cannot match
 * a specific response to a specific click. Instead we start a clock when an action is
 * fired and stop it on the next relevant S2C packet (slot update / inventory resync /
 * chat). Over many fires this yields a latency <i>distribution</i> — the honest,
 * client-side proxy for server-side GUI processing overhead.
 *
 * <p>Why it matters: it turns "the plugin handled it" into a number. A rate-limited
 * handler, an async-deferred handler, or a DB/connection-pool-bound handler all show
 * up as latency that grows (or responses that stop arriving — {@link #unmatched()})
 * under load. Compare the distribution at baseline vs. under concurrent stress to
 * answer "does GUI processing slow down when the server is under pressure?".
 *
 * <p>Composition helper — the owning module forwards markFire / markResponse /
 * tickTimeout and prints {@link #reportLines(String)}.
 */
public final class ResponseTimer {
    private long pendingSinceNanos = -1;
    private final List<Double> samplesMs = new ArrayList<>();
    private int unmatched;

    public void reset() { pendingSinceNanos = -1; samplesMs.clear(); unmatched = 0; }

    /** Call when an action packet (burst) is sent. Starts the clock if not already waiting. */
    public void markFire() { if (pendingSinceNanos < 0) pendingSinceNanos = System.nanoTime(); }

    /** Call for each candidate server response. Records RTT for the outstanding fire, if any. */
    public void markResponse() {
        if (pendingSinceNanos < 0) return;
        samplesMs.add((System.nanoTime() - pendingSinceNanos) / 1_000_000.0);
        pendingSinceNanos = -1;
    }

    /** Call each tick; a fire waiting longer than timeoutMs with no response is counted unmatched. */
    public void tickTimeout(double timeoutMs) {
        if (pendingSinceNanos < 0) return;
        if ((System.nanoTime() - pendingSinceNanos) / 1_000_000.0 > timeoutMs) {
            unmatched++;
            pendingSinceNanos = -1;
        }
    }

    public int count() { return samplesMs.size(); }
    public int unmatched() { return unmatched; }
    public boolean hasData() { return !samplesMs.isEmpty(); }

    public double min() { return samplesMs.isEmpty() ? -1 : Collections.min(samplesMs); }
    public double max() { return samplesMs.isEmpty() ? -1 : Collections.max(samplesMs); }
    public double avg() {
        return samplesMs.isEmpty() ? -1 : samplesMs.stream().mapToDouble(Double::doubleValue).average().orElse(-1);
    }

    public double percentile(double p) {
        if (samplesMs.isEmpty()) return -1;
        List<Double> s = new ArrayList<>(samplesMs);
        Collections.sort(s);
        int idx = (int) Math.ceil(p / 100.0 * s.size()) - 1;
        return s.get(Math.max(0, Math.min(s.size() - 1, idx)));
    }

    public List<String> reportLines(String label) {
        List<String> out = new ArrayList<>();
        if (samplesMs.isEmpty()) {
            out.add(String.format("  %s latency: no matched responses (%d unmatched — handler silent or rate-limited)",
                label, unmatched));
            return out;
        }
        out.add(String.format(
            "  %s latency (ms): min %.1f  avg %.1f  p50 %.1f  p95 %.1f  max %.1f  (n=%d, %d unmatched)",
            label, min(), avg(), percentile(50), percentile(95), max(), count(), unmatched));
        return out;
    }
}
