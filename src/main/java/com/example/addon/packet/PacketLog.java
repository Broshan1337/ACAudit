package com.example.addon.packet;

import net.minecraft.network.packet.Packet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Shared ring buffer for the Packet Inspector.
 *
 * Fed by the {@code PacketInspector} module's Send/Receive handlers (which, because
 * every module sends through the network handler, also captures the traffic of every
 * OTHER ACAudit module for free). Stores only a reference + timestamp + direction, so
 * capture is O(1) and safe at tick rate; rendering happens lazily in the GUI.
 *
 * Bounded by {@link #setCapacity}; oldest entries drop. Thread-safe because packet
 * events can arrive off the main thread.
 */
public final class PacketLog {
    private PacketLog() {}

    public record Entry(long id, long time, boolean outbound, Packet<?> packet) {}

    private static final Deque<Entry> BUF = new ArrayDeque<>();
    private static int capacity = 3000;
    private static boolean paused = false;
    private static long counter = 0;

    public static synchronized void add(boolean outbound, Packet<?> packet) {
        if (paused || packet == null) return;
        BUF.addLast(new Entry(counter++, System.currentTimeMillis(), outbound, packet));
        while (BUF.size() > capacity) BUF.removeFirst();
    }

    /** Newest-last snapshot. */
    public static synchronized List<Entry> snapshot() { return new ArrayList<>(BUF); }

    public static synchronized Entry byId(long id) {
        for (Entry e : BUF) if (e.id() == id) return e;
        return null;
    }

    public static synchronized void clear() { BUF.clear(); }
    public static synchronized int size() { return BUF.size(); }
    public static void setCapacity(int c) { capacity = Math.max(16, c); }
    public static void setPaused(boolean p) { paused = p; }
    public static boolean isPaused() { return paused; }
}
