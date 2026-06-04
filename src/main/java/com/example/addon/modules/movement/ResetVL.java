package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: ResetVL
 *
 * Emits a stream of small, deliberately-legal up/down hops on the ground. The
 * goal is to farm "clean" movement ticks. If your AC decays a player's
 * violation level whenever it sees normal-looking movement, a cheater runs
 * this between cheats to flush their VL back to zero and never crosses the
 * ban/kick threshold.
 *
 * Subtlety controls:
 *   drift-speed    — apply random X/Z velocity per hop. Tests whether an AC
 *                    also resets VL on lateral filler movement or only pure
 *                    vertical hops.
 *   hop-jitter     — ±random variation on upward velocity per hop. Tests
 *                    variance-based hop detection within the "legal" range.
 *   inter-hop-wait — ticks to wait between hops. Tests whether spaced-out
 *                    filler movement is caught by the same VL drain mechanism.
 *
 * DETECTION: do not decay VL on cheaply-produced filler movement. Use a leaky
 * bucket that only drains on movement that passed full physics validation, and
 * keep a separate long-window violation history that filler can't erase.
 */
public class ResetVL extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> hops = sgGeneral.add(new IntSetting.Builder()
        .name("hops").description("Number of clean hops to perform, then disable.")
        .defaultValue(10).range(1, 100).sliderRange(1, 30).build()
    );
    private final Setting<Double> driftSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("drift-speed")
        .description("Random ±X/Z velocity per hop. Tests whether lateral filler movement also drains VL.")
        .defaultValue(0.03).range(0.0, 0.2).sliderRange(0.0, 0.1).build()
    );
    private final Setting<Double> hopJitter = sgGeneral.add(new DoubleSetting.Builder()
        .name("hop-jitter")
        .description("Random ±variation on upward hop velocity. Tests variance-based detection within legal range.")
        .defaultValue(0.01).range(0.0, 0.05).sliderRange(0.0, 0.04).build()
    );
    private final Setting<Integer> interHopWait = sgGeneral.add(new IntSetting.Builder()
        .name("inter-hop-wait").description("Ticks to wait between hops. Tests spaced filler detection.")
        .defaultValue(0).range(0, 10).sliderRange(0, 8).build()
    );
    private final Setting<Boolean> interleaveViolation = sgGeneral.add(new BoolSetting.Builder()
        .name("interleave-violation")
        .description("Slip one deliberately-too-high hop in among the clean filler every N hops, and let the observer report whether the VL decayed faster than it accrued (i.e. the violation never gets punished).")
        .defaultValue(false).build()
    );
    private final Setting<Integer> cleanPerViolation = sgGeneral.add(new IntSetting.Builder()
        .name("clean-per-violation").description("Clean filler hops between each interleaved violation.")
        .defaultValue(5).range(1, 50).sliderRange(1, 20)
        .visible(interleaveViolation::get).build()
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
    private int done = 0;
    private int hopWaitCounter = 0;

    public ResetVL() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "reset-vl",
            "Performs clean filler hops to farm legal ticks. Tests whether your violation-level decay is gameable.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; done = 0; hopWaitCounter = 0;
        obs.onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        if (mc.player.isOnGround()) {
            if (hopWaitCounter > 0) { hopWaitCounter--; return; }

            double drift = driftSpeed.get();
            double dx = drift > 0 ? (Math.random() * 2 - 1) * drift : 0;
            double dz = drift > 0 ? (Math.random() * 2 - 1) * drift : 0;
            double jit = hopJitter.get() > 0 ? (Math.random() * 2 - 1) * hopJitter.get() : 0;
            double vel = Math.max(0.05, 0.1 + jit);
            // Every Nth hop, slip in a deliberately-too-high hop as the interleaved violation.
            boolean violation = interleaveViolation.get() && cleanPerViolation.get() > 0
                && done > 0 && done % cleanPerViolation.get() == 0;
            if (violation) {
                vel = 0.42 + jit;           // an obvious over-jump amid the clean filler
                dx = drift > 0 ? dx * 4 : 0.3;
                obs.markSent();
            }
            mc.player.setVelocity(dx, vel, dz);
            if (violation) info("Interleaved violation hop #%d (watching if VL ever flags it).", done);
            hopWaitCounter = interHopWait.get();
            done++;
            if (done >= hops.get()) toggle();
        } else if (mc.player.getVelocity().y > 0) {
            mc.player.setVelocity(mc.player.getVelocity().x, -0.1, mc.player.getVelocity().z);
        }
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
