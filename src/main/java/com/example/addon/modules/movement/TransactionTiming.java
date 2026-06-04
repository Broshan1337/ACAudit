package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Transaction Timing (transaction-clock compensation probe)
 *
 * Many proactive anticheats measure a player's latency from the vanilla TRANSACTION
 * — the server sends CommonPing(id) and times how long the client takes to reply
 * CommonPong(id) — and size their lag compensation from that, INDEPENDENTLY of
 * KeepAlive. This module delays the Pong replies (via TransactionAnchor) so the
 * latency the AC measures on its real compensation clock is inflated, then lets any
 * paired movement module's MovementObserver read whether a move corrected at honest
 * timing slips through under the widened window.
 *
 *   What it exploits: compensation sized from the transaction RTT the client can
 *     stretch by holding its Pong.
 *   Measurement AC: no transaction clock; unaffected.
 *   Simulation AC: grants extra tolerance proportional to the inflated transaction
 *     latency — the surface this probes.
 *   Intent AC: caps the window and cross-checks transaction latency against an
 *     independent RTT, refusing slack when the signals disagree.
 *   Fix (any well-implemented AC): bound the compensation window and reconcile
 *     transaction-derived latency with transport/movement latency before granting
 *     a wider tolerance.
 *
 * Unlike ping-spoof (which delays KeepAlive and is inert against transaction-based
 * compensation), this manipulates the exact reply the transaction clock reads. Pair
 * with a movement module and compare its outcome here vs. with timing honest. For a
 * self-contained boundary sweep, use compensation-boundary. Run on YOUR server.
 */
public class TransactionTiming extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final TransactionAnchor anchor = new TransactionAnchor(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0;

    public TransactionTiming() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "transaction-timing",
            "Delays transaction Pong replies to inflate the latency a transaction-based AC measures. Tests transaction-clock compensation (unlike ping-spoof's keepalive path).");
    }

    @Override
    public void onActivate() { ticksActive = 0; anchor.onActivate(); }

    @EventHandler
    private void onSend(PacketEvent.Send event) { anchor.onPongSend(event); }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { anchor.onReceive(event.packet); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        anchor.tick();
    }

    @Override
    public void onDeactivate() {
        anchor.flush();
        if (showStats.get()) {
            info("Summary: %d ticks active.", ticksActive);
            anchor.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        anchor.flush();
        if (autoDisable.get() && isActive()) toggle();
    }
}
