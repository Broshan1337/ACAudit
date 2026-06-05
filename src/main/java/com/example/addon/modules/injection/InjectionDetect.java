package com.example.addon.modules.injection;

import com.example.addon.audit.Finding;
import com.example.addon.audit.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared injection-result classifier, used by both {@code sql-injection-fuzz} (generic)
 * and {@code plugin-fuzz} (plugin-targeted) so they grade identically.
 *
 * Priority (highest-confidence first): a leaked DB error (near-proof) > a time-based
 * delay > an unexpected slow response > a TPS dip > a kick > silence > no signal.
 * All signals are client-side inference — candidates to confirm against the plugin's
 * query logs, never proof.
 */
public final class InjectionDetect {
    private InjectionDetect() {}

    /**
     * @param name           short finding label
     * @param category       report category (e.g. "SQLi", "PLUGIN")
     * @param payloadDelayMs expected delay for a time-based payload (0 otherwise)
     * @param firstReplySeen whether any chat reply arrived in the window
     * @param latency        ms from send to first reply, or -1 if none
     * @param baselineMs     calibrated normal reply latency, or -1 if unknown
     */
    public static Finding classify(String name, String category, int payloadDelayMs, String payloadText, String note,
                                   boolean firstReplySeen, long latency, double minTps, long baselineMs,
                                   int delayThresholdMs, double tpsFloor, boolean kicked, String kickReason,
                                   String leakEngine, String leakMatch) {
        List<String> det = new ArrayList<>();
        det.add("payload: " + payloadText);
        if (note != null && !note.isBlank()) det.add(note);
        if (latency >= 0) det.add("reply +" + latency + "ms" + (baselineMs >= 0 ? " (baseline " + baselineMs + "ms)" : ""));
        if (minTps < 20.0) det.add(String.format("min TPS %.1f", minTps));

        if (leakMatch != null) {
            det.add(0, "DB error leaked (" + leakEngine + "): matched \"" + leakMatch + "\"");
            return new Finding(name, category, Severity.CRITICAL, "ERROR-LEAK", det);
        }
        if (payloadDelayMs > 0 && latency >= Math.max(1500, (long) (payloadDelayMs * 0.6))
            && (baselineMs < 0 || latency > baselineMs + delayThresholdMs)) {
            return new Finding(name, category, Severity.HIGH, "TIME-DELAY", det);
        }
        if (payloadDelayMs == 0 && baselineMs >= 0 && latency > baselineMs + delayThresholdMs) {
            return new Finding(name, category, Severity.MEDIUM, "SLOW-RESPONSE", det);
        }
        if (minTps < tpsFloor) {
            return new Finding(name, category, Severity.MEDIUM, "TPS-DROP", det);
        }
        if (kicked) {
            if (kickReason != null) det.add("kick: \"" + kickReason + "\"");
            return new Finding(name, category, Severity.MEDIUM, "KICK", det);
        }
        if (baselineMs >= 0 && !firstReplySeen) {
            return new Finding(name, category, Severity.LOW, "SILENCE", det);
        }
        return new Finding(name, category, Severity.INFO, "no-signal", det);
    }

    /** Whether a finding counts as flagged (CRITICAL/HIGH/MEDIUM). */
    public static boolean flagged(Finding f) {
        return f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH || f.severity() == Severity.MEDIUM;
    }
}
