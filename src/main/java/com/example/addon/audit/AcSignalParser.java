package com.example.addon.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUDIT helper: anti-cheat detection-signal parser.
 *
 * Different anti-cheats announce a detection differently — some kick with a
 * branded reason, some post a staff/console chat line, some flash an action-bar
 * or title, some are entirely silent. This recognises the COMMON public message
 * shapes of the major anti-cheats so the runner can report DETECTED vs UNDETECTED
 * across AC software instead of just "ran it".
 *
 * It is deliberately GENERIC and heuristic: it matches anti-cheat brand names and
 * the generic vocabulary of a flag ("violation", "kicked for", "cheating",
 * "suspicious", ...). It can therefore false-positive on ordinary chat that
 * happens to contain those words — so a hit is reported as a SIGNAL to correlate
 * with logs, never as proof. This is honest client-side inference, not a server
 * oracle: we only see what the server chose to send our client.
 *
 * Run on YOUR server; tune the brand table to whatever AC you run.
 */
public final class AcSignalParser {
    private AcSignalParser() {}

    public record Signal(boolean detected, String ac, String channel, String raw) {
        public static final Signal NONE = new Signal(false, null, null, null);
    }

    /** Brand -> identifying substrings (lowercase). Order = priority. */
    private static final Map<String, List<String>> BRANDS = new LinkedHashMap<>();
    static {
        BRANDS.put("Grim",        List.of("grim", "grimac"));
        BRANDS.put("Vulcan",      List.of("vulcan"));
        BRANDS.put("Verus",       List.of("verus"));
        BRANDS.put("Matrix",      List.of("matrix"));
        BRANDS.put("Spartan",     List.of("spartan"));
        BRANDS.put("AAC",         List.of("aac", "advancedanticheat", "advanced anticheat"));
        BRANDS.put("NoCheatPlus", List.of("nocheatplus", "ncp"));
        BRANDS.put("Intave",      List.of("intave"));
        BRANDS.put("Polar",       List.of("polar"));
        BRANDS.put("Karhu",       List.of("karhu"));
        BRANDS.put("Themis",      List.of("themis"));
        BRANDS.put("Kauri",       List.of("kauri"));
        BRANDS.put("Horizon",     List.of("horizon"));
        BRANDS.put("Sentinel",    List.of("sentinel"));
        BRANDS.put("Witherac",    List.of("witherac", "wither ac"));
    }

    /** Generic flag vocabulary that signals a detection regardless of brand. */
    private static final List<String> GENERIC = List.of(
        "violation", "violations", " vl ", "vl:", "flagged", "flag for", "cheat",
        "cheating", "hacking", "hacker", "suspicious", "illegal", "anticheat",
        "anti-cheat", "anti cheat", "kicked for", "you have been kicked",
        "banned for", "unfair", "disallowed", "blocked movement", "invalid move",
        "moved too", "moved wrongly", "moving too fast", "flying is not enabled");

    /**
     * Classify a piece of server-originated text.
     * @param text    the message string (chat / action-bar / title / kick reason)
     * @param channel where it came from: "chat", "actionbar", "title", "kick"
     */
    public static Signal parse(String text, String channel) {
        if (text == null) return Signal.NONE;
        String s = text.toLowerCase();
        if (s.isBlank()) return Signal.NONE;

        for (Map.Entry<String, List<String>> e : BRANDS.entrySet()) {
            for (String token : e.getValue()) {
                if (s.contains(token)) return new Signal(true, e.getKey(), channel, text);
            }
        }
        for (String g : GENERIC) {
            if (s.contains(g)) return new Signal(true, "generic", channel, text);
        }
        return Signal.NONE;
    }
}
