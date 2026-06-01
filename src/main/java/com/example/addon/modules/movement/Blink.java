package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
 * The keystone evasion technique. Holds your outbound packets in a queue
 * instead of sending them, then flushes them all at once. During the hold
 * window the server still sees you at your last reported position, so anything
 * you do client-side (fly, run, teleport) is invisible until the flush - at
 * which point you "rubber-band" to your real position in one burst.
 *
 * If your AC can be blinked, every other cheat becomes invisible: the AC never
 * sees the illegal motion live, only a player who goes silent and then snaps.
 *
 * DETECTION: validate packet CADENCE, not per-packet legality. A legitimate
 * client sends ~20 movement packets/sec. Flag a player who sends no movement
 * for many ticks and then delivers a burst of queued moves / a large position
 * jump on resume. Setback-on-resume is not enough - detect the silence itself.
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
        .name("auto-flush")
        .description("Automatically release the queue after max-hold ticks.")
        .defaultValue(true).build()
    );
    private final Setting<Integer> maxHold = sgGeneral.add(new IntSetting.Builder()
        .name("max-hold-ticks")
        .description("Ticks to hold before auto-flushing (20 = 1s).")
        .defaultValue(40).range(1, 600).sliderRange(10, 200)
        .visible(autoFlush::get).build()
    );
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
    private int heldTicks = 0;
    private meteordevelopment.meteorclient.events.packets.PacketEvent.Send lastEvent;

    public Blink() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-blink",
            "Holds outbound packets then flushes them in a burst (lag switch). Tests packet-cadence / silence detection.");
    }

    @Override
    public void onActivate() { queue.clear(); heldTicks = 0; }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent); flush(); }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        ticksActive++;
        boolean hold = filter.get() == Filter.ALL || event.packet instanceof PlayerMoveC2SPacket;
        if (!hold) return;

        queue.add(event.packet);
        lastEvent = event;          // keep a handle to sendSilently through
        event.cancel();

        heldTicks++;
        if (autoFlush.get() && heldTicks >= maxHold.get()) flush();
    }

    private void flush() {
        if (queue.isEmpty()) return;
        info("Flushing %d held packets", queue.size());
        if (lastEvent != null) {
            for (Packet<?> p : queue) { lastEvent.sendSilently(p); packetsSent++; }
        } else if (mc.player != null) {
            var conn = mc.player.networkHandler.getConnection();
            for (Packet<?> p : queue) { conn.send(p); packetsSent++; }
        }
        queue.clear();
        heldTicks = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
