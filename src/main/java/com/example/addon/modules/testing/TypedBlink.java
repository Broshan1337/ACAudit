package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: Typed Blink (lagback)
 *
 * Blink that holds only ONE category of packet while letting everything else
 * through. Holding MOVEMENT while still sending COMBAT is the lagback exploit:
 * the server sees you frozen at a stale position while you attack from your real
 * one. Unlike full blink it never goes silent, so silence-detection misses it.
 *
 * What it tests: does combat resolve against server-authoritative position with
 * latency bounds, or against the last client-reported position? If holding
 * movement lets you hit from where you actually are while the server thinks
 * you're elsewhere, the AC trusts a stale, client-controlled position.
 *
 * Patch signal: resolve hits against the server's tracked position with a
 * bounded reconciliation window; flag a player whose action position and
 * reported position diverge beyond plausible latency.
 */
public class TypedBlink extends Module {
    public enum Hold { MOVEMENT, CONTAINER, COMBAT }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Hold> hold = sgGeneral.add(new EnumSetting.Builder<Hold>()
        .name("hold").description("Which packet category to withhold (others still send).")
        .defaultValue(Hold.MOVEMENT).build()
    );
    private final Setting<Integer> maxHold = sgGeneral.add(new IntSetting.Builder()
        .name("max-hold-ticks").description("Auto-release after this many ticks.")
        .defaultValue(20).range(1, 200).sliderRange(5, 100).build()
    );
    private final Setting<Keybind> releaseKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("release-key").description("Flush held packets immediately.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_G)).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    private final List<Packet<?>> queue = new ArrayList<>();
    private int held = 0;
    private boolean wasPressed = false;
    private PacketEvent.Send lastEvent;

    public TypedBlink() {
        super(AddonTemplate.TESTING_CATEGORY, "typed-blink",
            "Holds one packet category (e.g. movement) while sending the rest. The lagback exploit. Tests position-authority for combat.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; queue.clear(); held = 0; wasPressed = false; }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent); flush(); }

    private boolean matches(Packet<?> p) {
        return switch (hold.get()) {
            case MOVEMENT -> p instanceof PlayerMoveC2SPacket;
            case CONTAINER -> p instanceof ClickSlotC2SPacket;
            case COMBAT -> p instanceof PlayerInteractEntityC2SPacket || p instanceof HandSwingC2SPacket;
        };
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!matches(event.packet)) return;
        lastEvent = event;
        queue.add(event.packet);
        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        boolean p = releaseKey.get().isPressed();
        if (p && !wasPressed) { flush(); wasPressed = true; return; }
        wasPressed = p;
        held++;
        if (held >= maxHold.get()) flush();
    }

    private void flush() {
        if (queue.isEmpty()) { held = 0; return; }
        for (Packet<?> pkt : queue) {
                        if (lastEvent != null) {
                lastEvent.sendSilently(pkt);
                packetsSent++;
            } else if (mc.player != null) {
                mc.player.networkHandler.getConnection().send(pkt);
                packetsSent++;
            }
        }
        queue.clear();
        held = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
