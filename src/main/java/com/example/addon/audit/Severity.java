package com.example.addon.audit;

/**
 * Severity classification for an audit finding, shared by the runner and the
 * named scenarios so every report speaks the same language.
 *
 *   CRITICAL — confirmed server crash / disconnect-on-abuse, or a confirmed dupe
 *              (item count grew) / money created. Fix before exposing the server.
 *   HIGH     — an AC/economy bypass signal: cheat vector accepted with no
 *              detection, or a silent-accept on a dupe race (no echo, no resync).
 *   MEDIUM   — degraded performance (TPS dropped but recovered) or an ambiguous
 *              result that needs a human/log to confirm.
 *   LOW      — minor / informational deviation worth noting.
 *   INFO     — expected/clean outcome, context lines, platform identification.
 */
public enum Severity {
    CRITICAL, HIGH, MEDIUM, LOW, INFO;

    /** Short bracketed tag for text/markdown reports. */
    public String tag() {
        return "[" + name() + "]";
    }

    /** Sort rank — CRITICAL first. */
    public int rank() {
        return ordinal();
    }
}
