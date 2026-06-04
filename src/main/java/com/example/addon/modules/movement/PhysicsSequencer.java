package com.example.addon.modules.movement;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * Shared physics-sequence emitter (movement deep-coverage, the axis-1/6 engine).
 *
 * The whole point of the physics-consistency vectors is that EACH packet is
 * individually legal but the RELATIONSHIP between packets (acceleration, arc
 * shape, ground-state history, path continuity) is impossible. Building that by
 * hand in every module is error-prone, so this helper owns the running reported
 * position and emits {@link PlayerMoveC2SPacket} steps with explicit per-packet
 * control of (Δ, onGround, horizontalCollision). Modules supply the physics
 * logic; the sequencer just faithfully reports whatever (possibly impossible)
 * trajectory they describe and grades nothing (that is MovementObserver's job).
 *
 * apply-to-client controls whether the local client is also snapped to the faked
 * position (so the operator sees the desync) or only the REPORTED position moves
 * while the client stays put — the latter is the honest "server sees X, client is
 * at Y" probe and is the default.
 */
public final class PhysicsSequencer {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final Setting<Boolean> applyToClient;

    private double x, y, z;
    private boolean started;

    public PhysicsSequencer(SettingGroup g) {
        applyToClient = g.add(new BoolSetting.Builder()
            .name("apply-to-client")
            .description("Also snap the local client to the faked position (you see the desync). Off = only the reported position moves.")
            .defaultValue(false).build()
        );
    }

    /** Snapshot the player's current authoritative position as the sequence origin. */
    public void begin() {
        if (mc.player == null) return;
        x = mc.player.getX();
        y = mc.player.getY();
        z = mc.player.getZ();
        started = true;
    }

    public boolean started() { return started; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }

    /** Emit one step by a delta, with an explicit (possibly impossible) ground flag. */
    public void step(double dx, double dy, double dz, boolean onGround) {
        if (mc.player == null) return;
        x += dx; y += dy; z += dz;
        sendCurrent(onGround, false);
    }

    /** Emit one step to an absolute position. */
    public void stepTo(double nx, double ny, double nz, boolean onGround) {
        if (mc.player == null) return;
        x = nx; y = ny; z = nz;
        sendCurrent(onGround, false);
    }

    /** Emit only an onGround flag with no positional change (ground-state flicker). */
    public void groundFlag(boolean onGround) {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(onGround, false));
    }

    private void sendCurrent(boolean onGround, boolean horizontalCollision) {
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            x, y, z, onGround, horizontalCollision));
        if (applyToClient.get()) mc.player.setPosition(x, y, z);
    }

    /** Re-sync the running position to the client (call after the server corrects you). */
    public void resync() { started = false; }
}
