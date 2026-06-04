package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * AUDIT: Transaction Timing (ping/pong response-rate probe — axis 4)
 *
 * Some anticheats measure precise client response time with ping/transaction
 * packets (CommonPing → the client's CommonPong). This holds the client's Pong
 * replies by a configurable delay INDEPENDENTLY of movement-packet timing, so the
 * latency the AC infers from transactions disagrees with the player's real
 * movement timing.
 *
 *   What it exploits: that transaction-RTT and movement-RTT are measured
 *     separately and assumed to agree.
 *   Measurement AC: grants leniency proportional to the (spoofed) transaction RTT.
 *   Physics AC: still validates movement on its own clock — but if it sizes its
 *     tolerance window from transaction RTT, that window is now wrong.
 *   Intent AC: cross-checks the two latency estimates and flags the disagreement.
 *   Fix: derive a single authoritative latency from the transport, and reconcile
 *     transaction-RTT vs movement-cadence; a large disagreement is itself a signal.
 *
 * Pairs with any movement module: spoof a high transaction RTT here, then see (via
 * that module's MovementObserver) whether a move that gets set back at honest
 * timing is silently accepted under the inflated transaction window.
 * Run against your OWN server only.
 */
public class TransactionTiming extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> delayMs = sgGeneral.add(new IntSetting.Builder()
        .name("pong-delay-ms").description("How long to hold each Pong reply (flat fallback when realistic-latency is off).")
        .defaultValue(400).range(0, 10000).sliderRange(0, 2000).build()
    );

    private final LatencyModel latency = new LatencyModel(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private long activatedAt = 0;

    private record Held(Packet<?> packet, long releaseAt) {}
    private final Deque<Held> queue = new ArrayDeque<>();
    private PacketEvent.Send lastEvent;

    public TransactionTiming() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "transaction-timing",
            "Delays ping/transaction Pong replies independently of movement timing. Tests whether transaction-RTT leniency can be desynced from real movement timing.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; queue.clear(); activatedAt = System.currentTimeMillis();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
        releaseAll();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof CommonPongC2SPacket)) return;
        lastEvent = event;
        long elapsed = System.currentTimeMillis() - activatedAt;
        long delay = latency.nextDelayMs(delayMs.get(), elapsed);
        queue.add(new Held(event.packet, System.currentTimeMillis() + delay));
        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
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
            if (lastEvent != null) lastEvent.sendSilently(p);
            else if (mc.player != null) mc.player.networkHandler.getConnection().send(p);
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
