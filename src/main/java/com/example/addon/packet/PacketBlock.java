package com.example.addon.packet;

import net.minecraft.network.packet.Packet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-type block list for the inspector. While {@code packet-inspector} is active, any
 * packet whose type is blocked is cancelled (dropped) in Send/Receive — handy to silence
 * a spammy type (keep-alives, player-input) or to test how the server reacts when the
 * client stops sending or processing a packet type.
 *
 * Keyed by fully-qualified class name (stable across obfuscation within a runtime);
 * stores a readable label for the UI. Cancelling INBOUND packets can desync your client
 * — that's intended, it's an audit tool; unblock to recover.
 */
public final class PacketBlock {
    private PacketBlock() {}

    private static final Map<String, String> BLOCKED = new LinkedHashMap<>(); // fqcn -> label

    private static String key(Packet<?> p) { return p == null ? "" : p.getClass().getName(); }

    public static synchronized boolean isBlocked(Packet<?> p) { return BLOCKED.containsKey(key(p)); }

    /** Block this packet's type; {@code label} is shown in the UI. */
    public static synchronized void block(Packet<?> p, String label) {
        if (p != null) BLOCKED.put(key(p), label == null ? key(p) : label);
    }

    public static synchronized void unblock(String fqcn) { BLOCKED.remove(fqcn); }
    public static synchronized boolean isBlocked(String fqcn) { return BLOCKED.containsKey(fqcn); }
    public static synchronized Map<String, String> snapshot() { return new LinkedHashMap<>(BLOCKED); }
    public static synchronized int size() { return BLOCKED.size(); }
    public static synchronized void clear() { BLOCKED.clear(); }
}
