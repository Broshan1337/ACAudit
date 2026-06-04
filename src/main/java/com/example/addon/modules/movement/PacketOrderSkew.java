package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: Packet-Order Skew (within-tick ordering probe — axis 6)
 *
 * Within one tick a client sends several packet types. This buffers the tick's
 * outbound movement and an action (HandSwing) and flushes them in a deliberately
 * abnormal order, testing whether the AC validates packets in received order or
 * assumes a fixed pipeline (all movement → then combat → then inventory).
 *
 *   MOVE_FIRST   — vanilla-ish order (baseline).
 *   ACTION_FIRST — send the action before the move, so combat is validated against
 *                  the PRE-move position even though the move arrives same tick.
 *   INTERLEAVED  — action, move, action — a sequence the pipeline never expects.
 *
 *   What it exploits: an implicit assumption that packets arrive in a canonical
 *     per-tick order, creating a validation gap when they don't.
 *   Measurement AC: order-agnostic per-packet checks pass.
 *   Physics AC: may validate combat against a stale position if it processes the
 *     action before applying the same-tick move.
 *   Intent AC: notices an ordering no real client produces.
 *   Fix: order-independent validation, or canonicalize per-tick packet order
 *     before validating (apply all movement for the tick, then actions).
 *
 * SCOPE — which targets this bites: an AC that parallelizes or reorders a single
 * player's packets, or assumes a canonical pipeline. It is largely INERT against an
 * AC that processes each player's packets strictly in received order (most do), in
 * which case reordering them just changes the order they are validated in, with the
 * same per-packet result. A null result here means the target serializes correctly,
 * which IS the pass; a difference means an order assumption to fix.
 *
 * This is the non-flood ordering probe; it does NOT flood the queue. Run on YOUR
 * server only.
 */
public class PacketOrderSkew extends Module {
    public enum Order { MOVE_FIRST, ACTION_FIRST, INTERLEAVED }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Order> order = sgGeneral.add(new EnumSetting.Builder<Order>()
        .name("order").description("Flush order for the tick's buffered move + action.")
        .defaultValue(Order.ACTION_FIRST).build()
    );
    private final Setting<Boolean> injectSwing = sgGeneral.add(new BoolSetting.Builder()
        .name("inject-swing").description("Inject a HandSwing each tick as the action to reorder against movement.")
        .defaultValue(true).build()
    );

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
    private final List<PlayerMoveC2SPacket> moves = new ArrayList<>();
    private final List<HandSwingC2SPacket> actions = new ArrayList<>();
    private boolean flushing = false;

    public PacketOrderSkew() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "packet-order-skew",
            "Reorders the tick's move and action packets (action-first / interleaved). Tests whether the AC assumes a fixed per-tick packet order.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; moves.clear(); actions.clear(); flushing = false;
        obs.onActivate();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (flushing) return;
        if (event.packet instanceof PlayerMoveC2SPacket pm) { moves.add(pm); event.cancel(); }
        else if (event.packet instanceof HandSwingC2SPacket sw) { actions.add(sw); event.cancel(); }
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        if (injectSwing.get()) mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (mc.player == null || (moves.isEmpty() && actions.isEmpty())) return;
        flushing = true;
        switch (order.get()) {
            case MOVE_FIRST -> { flush(moves); flush(actions); }
            case ACTION_FIRST -> { flush(actions); flush(moves); }
            case INTERLEAVED -> {
                if (!actions.isEmpty()) send(actions.remove(0));
                flush(moves);
                flush(actions);
            }
        }
        flushing = false;
        moves.clear(); actions.clear();
        obs.markSent();
    }

    private void flush(List<? extends net.minecraft.network.packet.Packet<?>> list) {
        for (var p : list) send(p);
        list.clear();
    }

    private void send(net.minecraft.network.packet.Packet<?> p) {
        mc.player.networkHandler.sendPacket(p);
        packetsSent++;
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
