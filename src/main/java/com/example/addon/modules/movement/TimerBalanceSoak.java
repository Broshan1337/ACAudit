package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Timer Balance Soak (long-session timer-accumulator integrity probe)
 *
 * A timer check measures how many movement/flying packets a client sends versus
 * real elapsed time and keeps a running "balance" — small overshoots are tolerated
 * and decay, large or sustained ones flag. Most tests exercise a big obvious timer
 * (2.0×) for a few seconds. The untested case is a TINY sustained drift held for a
 * very long session: does the balance accumulator stay accurate over hundreds of
 * thousands of packets, or does sub-threshold drift slowly bank into exploitable
 * slack (or, conversely, into a false positive on a legitimately slightly-fast
 * client)?
 *
 * This module inflates the flying-packet rate by a configurable sub-1% amount using
 * extra zero-movement OnGroundOnly packets (each individually legal) and reports,
 * over time, whether the server ever corrects/kicks — quantifying the accumulator's
 * long-run behaviour.
 *
 *   What it exploits: a timer balance that decays/accumulates imperfectly over long
 *     sessions rather than being reconciled to an authoritative clock.
 *   Patch signal (any well-implemented AC): reconcile the timer balance against an
 *     authoritative server clock with bounded, symmetric decay so neither a tiny
 *     sustained drift nor floating-point accumulation can bank into slack over time.
 *
 * Leave running for a long session against your OWN server.
 */
public class TimerBalanceSoak extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> driftPercent = sgGeneral.add(new DoubleSetting.Builder()
        .name("drift-percent").description("Extra flying-packet rate as a percent (0.5 = +0.5%, deliberately sub-threshold).")
        .defaultValue(0.5).range(0.0, 5.0).sliderRange(0.0, 2.0).build()
    );
    private final Setting<Integer> reportSecs = sgGeneral.add(new IntSetting.Builder()
        .name("report-interval-secs").description("How often to print a running soak report.")
        .defaultValue(60).range(5, 600).sliderRange(15, 300).build()
    );

    private final PhysicsSequencer seq = new PhysicsSequencer(sgGeneral);
    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private double extra;
    private long extraPackets, activatedAt, lastReport;

    public TimerBalanceSoak() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "timer-balance-soak",
            "Inflates flying-packet rate by a sub-1% sustained drift over a long session. Tests timer-balance accumulator integrity over time.");
    }

    @Override
    public void onActivate() {
        extra = 0; extraPackets = 0;
        activatedAt = System.currentTimeMillis(); lastReport = activatedAt;
        obs.onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        obs.tick();

        extra += driftPercent.get() / 100.0;
        while (extra >= 1.0) {
            seq.groundFlag(mc.player.isOnGround()); // extra zero-movement flying packet — inflates the rate
            extra -= 1.0;
            extraPackets++;
            obs.markSent();
        }

        long now = System.currentTimeMillis();
        if (now - lastReport >= reportSecs.get() * 1000L) {
            lastReport = now;
            double mins = (now - activatedAt) / 60000.0;
            info("Soak: %.1f min, %d extra packets, %d setbacks, %d silent.%s",
                mins, extraPackets, obs.setbackCount(), obs.silentCount(),
                obs.kicked() ? " KICKED." : "");
        }
    }

    @Override
    public void onDeactivate() {
        double mins = (System.currentTimeMillis() - activatedAt) / 60000.0;
        info("Final: %.1f min, %d extra packets at +%.2f%% drift.", mins, extraPackets, driftPercent.get());
        obs.report(l -> info("%s", l));
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
