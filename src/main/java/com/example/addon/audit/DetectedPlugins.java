package com.example.addon.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared registry of plugins inferred from the client side, so the plugin-aware
 * fuzzer can target what was actually detected.
 *
 * Populated by {@code command-fingerprint} (plugin-namespaced command aliases seen
 * via tab-completion). Detection from a NON-OP client is heuristic — a normal player
 * cannot run /plugins or /version — so each entry carries a confidence:
 *
 *   CONFIRMED — a namespaced alias (e.g. "litebans:ban") was enumerated.
 *   SUSPECTED — a generic command that the plugin commonly owns exists.
 *   USER      — the auditor named it manually.
 */
public final class DetectedPlugins {
    private DetectedPlugins() {}

    public enum Confidence { CONFIRMED, SUSPECTED, USER }

    private static final Map<String, Confidence> PLUGINS = new LinkedHashMap<>();

    /** Record a detected plugin namespace (keeps the highest confidence seen). */
    public static synchronized void record(String namespace, Confidence c) {
        if (namespace == null || namespace.isBlank()) return;
        String key = namespace.toLowerCase().trim();
        Confidence prev = PLUGINS.get(key);
        if (prev == null || c.ordinal() < prev.ordinal()) PLUGINS.put(key, c);
    }

    public static synchronized Map<String, Confidence> snapshot() {
        return new LinkedHashMap<>(PLUGINS);
    }

    public static synchronized Confidence confidence(String namespace) {
        return PLUGINS.get(namespace == null ? "" : namespace.toLowerCase().trim());
    }

    public static synchronized boolean isEmpty() { return PLUGINS.isEmpty(); }
    public static synchronized void clear() { PLUGINS.clear(); }
}
