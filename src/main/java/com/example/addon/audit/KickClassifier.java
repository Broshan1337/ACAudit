package com.example.addon.audit;

import java.util.Locale;

/**
 * Classifies a disconnect/kick reason string so a crash audit can tell the difference
 * between the server DEFENDING correctly and the server BREAKING.
 *
 * <p>This is the honesty fix for the whole crash category: a Paper packet-limiter kick
 * ("Sent too many packets") is the server successfully throttling an abusive client —
 * a PASS, not a vulnerability. A timeout or an internal-exception disconnect after a
 * vector is a very different, CRITICAL result. Previously both surfaced identically as
 * "REJECTED / DISCONNECTED".
 *
 * <p>All inference is from the client-visible reason text (and the fact of the
 * disconnect), so it is a strong signal, not proof — confirm against server logs.
 */
public final class KickClassifier {
    private KickClassifier() {}

    public enum Kind {
        /** Paper/Netty packet-rate limiter or connection throttle — the server defended itself. */
        LIMITER("Paper packet-limiter / throttle engaged — the server defended itself (this is a PASS, not a crash)"),
        /** Kicked for an internal exception / malformed packet handling — a server-side fault. */
        INTERNAL_ERROR("internal exception / handler fault on the server — INVESTIGATE"),
        /** Connection timed out — often a hang or the client's send buffer backing up. */
        TIMEOUT("connection timed out — possible server hang or saturated send path"),
        /** A moderation ban/kick unrelated to resilience. */
        BAN("moderation ban/kick — not a resilience signal"),
        /** Flood/spam heuristics in an anticheat or chat filter. */
        ANTISPAM("anticheat/chat flood filter engaged — the server (or a plugin) defended itself"),
        /** No reason captured, or an unrecognised reason. */
        UNKNOWN("disconnect with no recognised reason — check server logs to classify");

        public final String explanation;
        Kind(String explanation) { this.explanation = explanation; }
    }

    /** True when the kind means the server (or Paper/a plugin) defended correctly, not a fault. */
    public static boolean isDefensive(Kind k) {
        return k == Kind.LIMITER || k == Kind.ANTISPAM || k == Kind.BAN;
    }

    public static Kind classify(String reason) {
        if (reason == null) return Kind.UNKNOWN;
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.isBlank()) return Kind.UNKNOWN;

        // Paper / Netty packet-rate limiter and connection throttles.
        if (contains(r, "too many packets", "exceeded_packet_rate", "packet rate", "packet limit",
                "sent too many", "rate limit", "throttle", "connection throttled", "reconnect too fast"))
            return Kind.LIMITER;

        // Internal exceptions / malformed-packet handling faults.
        if (contains(r, "internal exception", "internal server error", "internal_exception",
                "invalid packet", "protocol error", "unexpected packet", "packetdecoder",
                "badly compressed", "readerindex", "index out of bounds", "nullpointer",
                "outofmemory", "stackoverflow"))
            return Kind.INTERNAL_ERROR;

        // Timeouts / hangs.
        if (contains(r, "timed out", "timeout", "took too long", "keepalive", "keep alive", "read timed out"))
            return Kind.TIMEOUT;

        // Moderation.
        if (contains(r, "banned", "you are banned", "kicked by", "temp ban", "tempban"))
            return Kind.BAN;

        // Anticheat / chat flood filters.
        if (contains(r, "spam", "slow down", "too fast", "flood", "kicked for", "please wait", "cheat"))
            return Kind.ANTISPAM;

        return Kind.UNKNOWN;
    }

    /** One-line, human-readable classification for a report. */
    public static String describe(String reason) {
        Kind k = classify(reason);
        String quoted = (reason == null || reason.isBlank()) ? "(no reason text)" : "\"" + trim(reason) + "\"";
        return k.name() + " — " + k.explanation + " · reason: " + quoted;
    }

    private static boolean contains(String hay, String... needles) {
        for (String n : needles) if (hay.contains(n)) return true;
        return false;
    }

    private static String trim(String s) { return s.length() > 100 ? s.substring(0, 97) + "..." : s; }
}
