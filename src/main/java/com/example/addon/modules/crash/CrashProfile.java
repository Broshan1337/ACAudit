package com.example.addon.modules.crash;

import com.example.addon.audit.RealismTag;
import com.example.addon.audit.Severity;

import java.util.List;

/**
 * Structured, user-facing metadata for a crash/stress vector — the crash-category
 * sibling of {@link com.example.addon.audit.Disclosure}.
 *
 * <p>Crash modules are the scariest category for a first-time user, so every one must
 * state, in plain English and up front: whether a real threat actor could trigger it
 * ({@link RealismTag}), how serious a success is, a recommended SAFE first-run intensity,
 * and what to watch for in the server logs. Making it a typed field (printed on activate,
 * and collatable by the audit pipeline) turns "it fired" into an actionable finding, and
 * lets CONTRIBUTING enforce it on new crash modules.
 *
 * @param realism      who can actually trigger this on a default Paper server
 * @param severity     how serious a successful crash/lag is against production
 * @param vulnerability plain-English: what weakness this tests and why a server must handle it
 * @param safeIntensity a recommended conservative first-run setting (e.g. "10 packets/tick, PROBE mode")
 * @param watchFor      what to watch in server logs / metrics while it runs
 */
public record CrashProfile(RealismTag realism, Severity severity, String vulnerability,
                           String safeIntensity, String watchFor) {

    public static CrashProfile of(RealismTag realism, Severity severity, String vulnerability,
                                  String safeIntensity, String watchFor) {
        return new CrashProfile(realism, severity, vulnerability, safeIntensity, watchFor);
    }

    /** Human-readable lines for chat / report output, printed on module activate. */
    public List<String> lines() {
        return List.of(
            "  " + realism.tag() + " severity " + severity + " — " + realism.explanation,
            "    tests:        " + vulnerability,
            "    safe first run: " + safeIntensity,
            "    watch logs for: " + watchFor);
    }

    /** Compact one-line form. */
    public String oneLine() {
        return realism.tag() + "[" + severity + "] " + vulnerability;
    }
}
