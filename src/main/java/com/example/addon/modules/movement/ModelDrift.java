package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: Model Drift (error-accumulation probe — axis 2)
 *
 * Rewrites each outbound position packet with a TINY constant bias in one
 * direction (default 0.02 b/tick). Every individual packet is well within
 * tolerance — the move from the previous reported position is legal — but the
 * bias never reverses, so over time the server's authoritative model and the
 * real client position diverge without any single packet ever crossing a
 * threshold.
 *
 *   What it exploits: that per-tick error tolerance, applied repeatedly without
 *     reconciliation, compounds into a large position error.
 *   Measurement AC: passes every packet (each delta is legal).
 *   Physics AC: passes too if it only bounds per-tick error.
 *   Intent / well-built AC: periodically HARD-reconciles the client to its
 *     authoritative model and flags cumulative drift, because no real player
 *     produces a perfectly one-directional sub-threshold bias forever.
 *   Fix: bound CUMULATIVE position error, not just per-tick error; periodically
 *     snap-and-verify the client against the server model and reset the budget.
 *
 * MovementObserver reports if/when the server finally corrects you and by how
 * much — a large eventual correction means the model reconciled; no correction
 * means the drift budget is effectively unbounded. Run against your OWN server.
 */
public class ModelDrift extends Module {
    public enum Direction { FORWARD, BACK, RIGHT, LEFT }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> perTick = sgGeneral.add(new DoubleSetting.Builder()
        .name("drift-per-tick").description("Bias added to the reported position each tick (b). Keep tiny to stay sub-threshold.")
        .defaultValue(0.02).range(0.001, 0.2).sliderRange(0.001, 0.1).build()
    );
    private final Setting<Direction> direction = sgGeneral.add(new EnumSetting.Builder<Direction>()
        .name("direction").description("Drift direction relative to facing.")
        .defaultValue(Direction.RIGHT).build()
    );
    private final Setting<Double> maxDrift = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-drift").description("Stop accumulating past this total offset (0 = unbounded — see if the AC ever reconciles).")
        .defaultValue(0.0).range(0.0, 50.0).sliderRange(0.0, 20.0).build()
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
    private double accumX = 0, accumZ = 0;
    private boolean sending = false;

    public ModelDrift() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "model-drift",
            "Adds a tiny constant sub-threshold bias to every reported position. Tests whether the AC bounds cumulative drift or only per-tick error.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; accumX = accumZ = 0; sending = false;
        obs.onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        obs.tick();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (sending) return;
        if (mc.player == null) return;
        if (!(event.packet instanceof PlayerMoveC2SPacket pm) || !pm.changesPosition()) return;

        // Advance the accumulated bias (capped if a max is set).
        double step = perTick.get();
        double cap = maxDrift.get();
        double yaw = Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);     // forward
        double bx, bz;
        switch (direction.get()) {
            case FORWARD -> { bx = fx;  bz = fz; }
            case BACK    -> { bx = -fx; bz = -fz; }
            case RIGHT   -> { bx = fz;  bz = -fx; }
            default      -> { bx = -fz; bz = fx; }           // LEFT
        }
        if (cap <= 0 || Math.hypot(accumX, accumZ) < cap) {
            accumX += bx * step;
            accumZ += bz * step;
        }

        double x = pm.getX(mc.player.getX()) + accumX;
        double y = pm.getY(mc.player.getY());
        double z = pm.getZ(mc.player.getZ()) + accumZ;

        event.cancel();
        sending = true;
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            x, y, z, pm.isOnGround(), pm.horizontalCollision()));
        sending = false;
        packetsSent++;
        obs.markSent();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent, %.2f b total drift.",
                ticksActive, packetsSent, Math.hypot(accumX, accumZ));
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
