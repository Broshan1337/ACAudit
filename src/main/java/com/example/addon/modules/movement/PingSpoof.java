package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * AUDIT: PingSpoof
 *
 * Delays the client's outbound KeepAlive responses by a fixed time, inflating
 * the latency the server measures for this player. Latency-compensated checks
 * (knockback, hit reach, movement tolerance) widen for "laggy" clients, so a
 * cheater manufactures lag to buy slack the AC then grants them.
 *
 * Subtlety controls:
 *   jitter-ms      — ±random variation per KeepAlive delay. Tests whether the
 *                    AC detects constant artificial ping or also detects jittered
 *                    ping (harder to distinguish from real network variance).
 *   escalate-step  — delay grows by N ms per KeepAlive, simulating a slowly
 *                    degrading connection to see if the AC adapts its tolerance
 *                    window dynamically or uses a fixed threshold.
 *
 * DETECTION: measure RTT at the proxy / from TCP-level keepalives, not from the
 * client-acknowledged application KeepAlive the client fully controls. A player
 * whose application-ping is high but whose TCP round-trip is low is spoofing.
 *
 * SCOPE — which targets this bites: an AC that derives latency compensation from
 * the application KeepAlive cycle. It is INERT against an AC that times off the
 * vanilla TRANSACTION (CommonPing/Pong) instead — that clock is independent of
 * KeepAlive, so delaying KeepAlive buys no compensation slack there. To probe a
 * transaction-based compensation system use transaction-timing ({@link
 * TransactionTiming}) / compensation-boundary ({@link CompensationBoundary}), which
 * manipulate the Pong reply the transaction clock actually reads.
 */
public class PingSpoof extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> maxWindow = sgGeneral.add(new BoolSetting.Builder()
        .name("max-window")
        .description("Hold responses ~29s (just under the ~30s server timeout) for the maximum lag window. Overrides delay-ms.")
        .defaultValue(false).build()
    );
    private final Setting<Integer> delayMs = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ms").description("How long to hold KeepAlive responses (added apparent ping).")
        .defaultValue(500).range(0, 10000).sliderRange(0, 2000)
        .visible(() -> !maxWindow.get()).build()
    );
    private final Setting<Integer> jitterMs = sgGeneral.add(new IntSetting.Builder()
        .name("jitter-ms")
        .description("Random ±ms added to each KeepAlive delay. Tests jittered-ping vs. constant-ping detection.")
        .defaultValue(0).range(0, 200).sliderRange(0, 100).build()
    );
    private final Setting<Integer> escalateStep = sgGeneral.add(new IntSetting.Builder()
        .name("escalate-step")
        .description("Delay increases by this many ms per KeepAlive sent. Tests dynamic tolerance adaptation.")
        .defaultValue(0).range(0, 5000).sliderRange(0, 1000).build()
    );

    // Because PingSpoof delays ONLY KeepAlive (not movement), it already creates a
    // keepalive-RTT-vs-movement-timing divergence; realistic-latency makes that
    // divergence look organic to probe whether the AC trusts it more.
    private final LatencyModel latency = new LatencyModel(sgGeneral);
    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private long keepAliveCount = 0;
    private long activatedAt = 0;

    private record Held(Packet<?> packet, long releaseAt) {}
    private final Deque<Held> queue = new ArrayDeque<>();
    private PacketEvent.Send lastEvent;

    public PingSpoof() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ping-spoof",
            "Delays KeepAlive responses to fake high ping. Tests whether latency checks can be gamed by the client.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; queue.clear(); keepAliveCount = 0; activatedAt = System.currentTimeMillis(); obs.onActivate(); }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
        releaseAll();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof KeepAliveC2SPacket)) return;
        lastEvent = event;
        long base = maxWindow.get() ? 29000L : delayMs.get();
        long delay;
        if (latency.realistic()) {
            delay = latency.nextDelayMs(base, System.currentTimeMillis() - activatedAt);
        } else {
            long jit = jitterMs.get() > 0 ? (long) ((Math.random() * 2 - 1) * jitterMs.get()) : 0;
            long esc = keepAliveCount * escalateStep.get();
            delay = base + jit + esc;
        }
        keepAliveCount++;
        queue.add(new Held(event.packet, System.currentTimeMillis() + delay));
        event.cancel();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        obs.tick();
        long now = System.currentTimeMillis();
        while (!queue.isEmpty() && queue.peek().releaseAt() <= now) {
            Packet<?> p = queue.poll().packet();
            if (lastEvent != null) { lastEvent.sendSilently(p); packetsSent++; }
            else if (mc.player != null) { mc.player.networkHandler.getConnection().send(p); packetsSent++; }
        }
    }

    private void releaseAll() {
        while (!queue.isEmpty()) {
            Packet<?> p = queue.poll().packet();
            if (lastEvent != null) { lastEvent.sendSilently(p); }
            else if (mc.player != null) { mc.player.networkHandler.getConnection().send(p); }
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
