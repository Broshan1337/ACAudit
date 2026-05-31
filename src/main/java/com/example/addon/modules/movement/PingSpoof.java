package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
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
 * DETECTION: measure RTT at the proxy / from TCP-level keepalives, not from the
 * client-acknowledged application KeepAlive the client fully controls. A player
 * whose application-ping is high but whose TCP round-trip is low is spoofing.
 */
public class PingSpoof extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> maxWindow = sgGeneral.add(new BoolSetting.Builder()
        .name("max-window")
        .description("Keepalive starvation: hold responses ~29s, just under the ~30s server timeout, for the maximum lag window without disconnecting. Overrides delay-ms.")
        .defaultValue(false).build()
    );
    private final Setting<Integer> delayMs = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ms")
        .description("How long to hold KeepAlive responses (added apparent ping).")
        .defaultValue(500).range(0, 10000).sliderRange(0, 2000)
        .visible(() -> !maxWindow.get()).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private record Held(Packet<?> packet, long releaseAt) {}
    private final Deque<Held> queue = new ArrayDeque<>();
    private PacketEvent.Send lastEvent;

    public PingSpoof() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ping-spoof",
            "Delays KeepAlive responses to fake high ping. Tests whether latency checks can be gamed by the client.");
    }

    @Override
    public void onActivate() { queue.clear(); }

    @Override
    public void onDeactivate() { releaseAll(); }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof KeepAliveC2SPacket)) return;
        lastEvent = event;
        long delay = maxWindow.get() ? 29000 : delayMs.get();
        queue.add(new Held(event.packet, System.currentTimeMillis() + delay));
        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        long now = System.currentTimeMillis();
        while (!queue.isEmpty() && queue.peek().releaseAt() <= now) {
            Packet<?> p = queue.poll().packet();
            if (lastEvent != null) lastEvent.sendSilently(p);
            else if (mc.player != null) mc.player.networkHandler.getConnection().send(p);
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
