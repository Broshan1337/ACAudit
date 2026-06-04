package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: Blink (lag switch)
 *
 * Holds outbound packets in a queue and flushes them all at once. During the
 * hold window the server still sees you at your last reported position, so
 * anything done client-side is invisible until the flush.
 *
 * If your AC can be blinked, every other cheat becomes invisible: the AC never
 * sees the illegal motion live, only a player who goes silent and then snaps.
 *
 * Subtlety controls:
 *   jitter-ticks       — randomises the flush point by ±N ticks so the silence
 *                        window isn't perfectly periodic (tests cadence-pattern
 *                        detection vs. cadence-presence detection).
 *   flush-spread-ticks — spread the flushed packets across N ticks instead of
 *                        sending them all in one burst, simulating a more
 *                        realistic network recovery.
 *
 * Combination: Blink+AntiSetback is the canonical full evasion combo — go
 * silent, do illegal movement, flush, drop the resulting correction. Also pair
 * with StealthFly JITTER: fly during silence, flush movement burst.
 *
 * DETECTION: validate packet CADENCE. A legitimate client sends ~20 movement
 * packets/sec. Flag a player who goes silent then delivers a position burst.
 */
public class Blink extends Module {
    public enum Filter { MOVEMENT_ONLY, ALL }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Filter> filter = sgGeneral.add(new EnumSetting.Builder<Filter>()
        .name("filter")
        .description("MOVEMENT_ONLY holds only move packets (stay interactive). ALL holds everything.")
        .defaultValue(Filter.MOVEMENT_ONLY).build()
    );
    private final Setting<Boolean> autoFlush = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-flush").description("Automatically release the queue after max-hold ticks.")
        .defaultValue(true).build()
    );
    private final Setting<Integer> maxHold = sgGeneral.add(new IntSetting.Builder()
        .name("max-hold-ticks").description("Base ticks to hold before auto-flushing (20 = 1s).")
        .defaultValue(40).range(1, 600).sliderRange(10, 200)
        .visible(autoFlush::get).build()
    );
    private final Setting<Integer> jitterTicks = sgGeneral.add(new IntSetting.Builder()
        .name("jitter-ticks")
        .description("Random ±ticks added to max-hold-ticks each cycle. Tests cadence-pattern vs. cadence-presence detection.")
        .defaultValue(0).range(0, 40).sliderRange(0, 20)
        .visible(autoFlush::get).build()
    );
    private final Setting<Integer> flushSpreadTicks = sgGeneral.add(new IntSetting.Builder()
        .name("flush-spread-ticks")
        .description("Spread flushed packets across this many ticks (0 = instant burst). Simulates realistic network recovery.")
        .defaultValue(0).range(0, 10).sliderRange(0, 5).build()
    );
    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable (and flush) when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    private final List<Packet<?>> queue = new ArrayList<>();
    private final List<Packet<?>> flushQueue = new ArrayList<>();
    private int heldTicks = 0;
    private int targetHold = 0;
    private int flushPerTick = Integer.MAX_VALUE;
    private PacketEvent.Send lastEvent;

    public Blink() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-blink",
            "Holds outbound packets then flushes them in a burst (lag switch). Tests packet-cadence / silence detection.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        queue.clear(); flushQueue.clear();
        heldTicks = 0;
        targetHold = computeTarget();
        obs.onActivate();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
        flush();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        obs.tick();

        // Drain spread flush queue
        if (!flushQueue.isEmpty()) {
            int toSend = flushPerTick == Integer.MAX_VALUE ? flushQueue.size() : Math.min(flushPerTick, flushQueue.size());
            for (int i = 0; i < toSend && !flushQueue.isEmpty(); i++) {
                sendPacket(flushQueue.remove(0));
            }
        }
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        boolean hold = filter.get() == Filter.ALL || event.packet instanceof PlayerMoveC2SPacket;
        if (!hold) return;

        queue.add(event.packet);
        lastEvent = event;
        event.cancel();

        heldTicks++;
        if (autoFlush.get() && heldTicks >= targetHold) flush();
    }

    private void flush() {
        if (queue.isEmpty()) return;
        info("Flushing %d held packets%s", queue.size(),
            flushSpreadTicks.get() > 0 ? " over " + flushSpreadTicks.get() + " ticks" : "");

        int spread = flushSpreadTicks.get();
        if (spread == 0) {
            for (Packet<?> p : queue) sendPacket(p);
        } else {
            flushQueue.addAll(queue);
            // packets per tick = queue size / spread ticks, minimum 1
            flushPerTick = Math.max(1, (int) Math.ceil((double) queue.size() / spread));
        }
        queue.clear();
        heldTicks = 0;
        targetHold = computeTarget();
        obs.markSent();
    }

    private void sendPacket(Packet<?> p) {
        if (lastEvent != null) { lastEvent.sendSilently(p); packetsSent++; }
        else if (mc.player != null) { mc.player.networkHandler.getConnection().send(p); packetsSent++; }
    }

    private int computeTarget() {
        int base = maxHold.get();
        int j = jitterTicks.get();
        return j > 0 ? Math.max(1, base + (int) ((Math.random() * 2 - 1) * j)) : base;
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
