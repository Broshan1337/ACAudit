package com.example.addon.modules.movement;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared transaction/compensation helper (modern-AC deep-coverage backbone).
 *
 * Many proactive anticheats do NOT measure a player's latency from keepalive.
 * They sandwich state changes between two vanilla transactions — the server sends
 * CommonPingS2CPacket(id) and the client must reply CommonPongC2SPacket(id) — so
 * the server learns the EXACT moment a client acknowledged a given point in the
 * packet stream and sizes its lag-compensation window from that. This is the
 * legitimate mechanism that makes lag compensation precise; it is also the
 * surface that any latency-compensating AC must defend.
 *
 * Honest boundary on what a CLIENT can do here: the client cannot initiate a
 * transaction (CommonPing is server→client only) and cannot directly read the
 * server-measured RTT. What it CAN do, and what this helper exposes:
 *
 *   - Treat every incoming transaction as a precise serialization boundary so a
 *     probe can place an action immediately relative to a known acknowledged tick
 *     (transactionBoundary()).
 *   - Observe how often the target transacts (cadence) — an AC that pings every
 *     tick has a much tighter compensation grid than one that pings rarely.
 *   - Inject a controlled, measurable delay before replying Pong, inflating the
 *     latency the AC measures for this player SPECIFICALLY, to probe how much extra
 *     tolerance that buys (the compensation-window boundary).
 *
 * What a well-implemented AC should do (the generic patch signal every module
 * built on this helper reports against): bound the compensation window with a
 * hard cap, and cross-check transaction-derived latency against an independently
 * measured round-trip (transport RTT, movement cadence). A player whose
 * transaction latency is high but whose other latency signals are low is gaming
 * the compensation system, and the extra tolerance must NOT be granted.
 *
 * Composition, not inheritance: the owning module forwards onActivate / tick /
 * onReceive, routes its Pong sends through onPongSend(), and prints report().
 */
public final class TransactionAnchor {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final Setting<Boolean> track;
    private final Setting<Integer> injectMs;
    private final Setting<Integer> jitterMs;

    private int seen;
    private int lastId;
    private int ticksSinceLast = -1;
    private boolean boundaryThisTick;

    // cadence (exponential moving average of ticks between transactions)
    private double cadence = -1;

    // injected-latency pong hold
    private record Held(Packet<?> packet, long releaseAt) {}
    private final Deque<Held> queue = new ArrayDeque<>();
    private PacketEvent.Send lastSend;
    private long maxInjected;
    private int injectOverrideMs = -1; // >=0 lets a seeking module drive the injected latency programmatically

    public TransactionAnchor(SettingGroup g) {
        track = g.add(new BoolSetting.Builder()
            .name("track-transactions")
            .description("Watch the server's ping/transaction stream and use each one as a precise acknowledged-tick boundary.")
            .defaultValue(true).build()
        );
        injectMs = g.add(new IntSetting.Builder()
            .name("inject-latency-ms")
            .description("Hold each transaction Pong reply this long, inflating the latency the AC measures for you specifically (0 = reply honestly). Probes the compensation-window boundary.")
            .defaultValue(0).range(0, 10000).sliderRange(0, 2000)
            .visible(track::get).build()
        );
        jitterMs = g.add(new IntSetting.Builder()
            .name("latency-jitter-ms")
            .description("Random ±ms on each injected Pong delay, so the manufactured latency looks like organic network variance rather than a flat offset.")
            .defaultValue(0).range(0, 500).sliderRange(0, 200)
            .visible(() -> track.get() && injectMs.get() > 0).build()
        );
    }

    public boolean enabled() { return track.get(); }
    public int transactionsSeen() { return seen; }
    public int ticksSinceLastTransaction() { return ticksSinceLast; }
    public int lastTransactionId() { return lastId; }
    public long injectedLatencyMs() { return maxInjected; }
    /** EMA of ticks between transactions, or -1 if not yet known. */
    public double cadenceTicks() { return cadence; }
    /** Let a seeking module drive the injected Pong latency programmatically; -1 returns control to the setting. */
    public void setInjectOverrideMs(int ms) { injectOverrideMs = ms; }

    public void onActivate() {
        seen = 0; lastId = 0; ticksSinceLast = -1; boundaryThisTick = false;
        cadence = -1; maxInjected = 0; queue.clear();
    }

    /** Forward every inbound packet. Returns true if it was a transaction ping. */
    public boolean onReceive(Object packet) {
        if (!track.get()) return false;
        if (packet instanceof CommonPingS2CPacket p) {
            seen++;
            lastId = p.getParameter();
            if (ticksSinceLast >= 0) {
                cadence = cadence < 0 ? ticksSinceLast : cadence * 0.7 + ticksSinceLast * 0.3;
            }
            ticksSinceLast = 0;
            boundaryThisTick = true;
            return true;
        }
        return false;
    }

    /**
     * Route the client's outbound Pong replies through here. When inject-latency is
     * on, the reply is held and re-sent later. Returns true if the helper took
     * ownership of the packet (the module must NOT also send it).
     */
    public boolean onPongSend(PacketEvent.Send event) {
        int base = injectOverrideMs >= 0 ? injectOverrideMs : injectMs.get();
        if (!track.get() || base <= 0) return false;
        if (!(event.packet instanceof CommonPongC2SPacket)) return false;
        lastSend = event;
        long jit = jitterMs.get() > 0 ? (long) ((Math.random() * 2 - 1) * jitterMs.get()) : 0;
        long delay = Math.max(0, base + jit);
        maxInjected = Math.max(maxInjected, delay);
        queue.add(new Held(event.packet, System.currentTimeMillis() + delay));
        event.cancel();
        return true;
    }

    public void tick() {
        if (!track.get()) return;
        if (ticksSinceLast >= 0) ticksSinceLast++;
        long now = System.currentTimeMillis();
        while (!queue.isEmpty() && queue.peek().releaseAt() <= now) {
            Packet<?> p = queue.poll().packet();
            if (lastSend != null) lastSend.sendSilently(p);
            else if (mc.player != null) mc.player.networkHandler.getConnection().send(p);
        }
    }

    /** Consume the "a transaction arrived since the last call" flag. True for the tick a ping landed. */
    public boolean transactionBoundary() {
        boolean b = boundaryThisTick;
        boundaryThisTick = false;
        return b;
    }

    /** Release any held Pongs immediately (call on deactivate so we never strand a reply). */
    public void flush() {
        while (!queue.isEmpty()) {
            Packet<?> p = queue.poll().packet();
            if (lastSend != null) lastSend.sendSilently(p);
            else if (mc.player != null) mc.player.networkHandler.getConnection().send(p);
        }
    }

    public void report(Consumer<String> printer) {
        if (!track.get()) return;
        for (String l : reportLines()) printer.accept(l);
    }

    public List<String> reportLines() {
        List<String> out = new ArrayList<>();
        if (!track.get()) return out;
        out.add(String.format("  Transactions: %d seen, cadence ≈ %s",
            seen, cadence < 0 ? "unknown" : String.format("%.1f ticks", cadence)));
        if (seen == 0)
            out.add("  → no transactions observed: target likely uses no transaction-based compensation (keepalive/position-only).");
        if (maxInjected > 0)
            out.add(String.format("  Injected up to %d ms of Pong latency — compare tolerance vs. honest run to read the compensation window.", maxInjected));
        return out;
    }
}
