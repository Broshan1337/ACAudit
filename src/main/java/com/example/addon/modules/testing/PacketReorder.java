package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AUDIT: Packet Reorder
 *
 * Buffers your outbound movement/action packets for a short window, then
 * releases them out of order (reversed or shuffled). Tests packet handlers that
 * assume causal ordering - e.g. position updates arriving newest-first, or an
 * action landing before the movement that should precede it.
 *
 * Patch signal: validate temporal/causal order on ingest; reject or clamp
 * position sequences that move backwards in time; never assume packet order
 * implies event order without a sequence/tick stamp.
 */
public class PacketReorder extends Module {
    public enum Mode { REVERSE, SHUFFLE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").defaultValue(Mode.REVERSE).build()
    );
    private final Setting<Integer> windowTicks = sgGeneral.add(new IntSetting.Builder()
        .name("window-ticks").description("Ticks to buffer before releasing reordered.")
        .defaultValue(4).range(2, 40).sliderRange(2, 20).build()
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

    private final List<Packet<?>> buffer = new ArrayList<>();
    private int held = 0;
    private PacketEvent.Send lastEvent;

    public PacketReorder() {
        super(AddonTemplate.TESTING_CATEGORY, "packet-reorder",
            "Buffers movement/action packets and releases them reversed or shuffled. Tests causal-order validation.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; buffer.clear(); held = 0; }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent); releaseInOrder(); }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerMoveC2SPacket || event.packet instanceof PlayerActionC2SPacket)) return;
        lastEvent = event;
        buffer.add(event.packet);
        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        held++;
        if (held < windowTicks.get()) return;

        if (mode.get() == Mode.REVERSE) Collections.reverse(buffer);
        else Collections.shuffle(buffer);
        releaseInOrder();
    }

    private void releaseInOrder() {
        if (buffer.isEmpty()) { held = 0; return; }
        for (Packet<?> pkt : buffer) {
                        if (lastEvent != null) {
                lastEvent.sendSilently(pkt);
                packetsSent++;
            } else if (mc.player != null) {
                mc.player.networkHandler.getConnection().send(pkt);
                packetsSent++;
            }
        }
        buffer.clear();
        held = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
