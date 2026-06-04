package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Vehicle Move (boat / minecart fly & speed)
 *
 * The biggest movement blind spot on most ACs: vehicle position arrives in
 * VehicleMoveC2SPacket, a DIFFERENT path from player movement, and is frequently
 * validated far more loosely — or not at all.
 *
 * Subtlety controls:
 *   speed-jitter       — ±random variation on horizontal speed per tick. Tests
 *                        variance-based vehicle speed detection.
 *   direction-noise    — rotate the yaw by ±N degrees when computing the direction
 *                        vector. Makes the vehicle drift slightly, mimicking a player
 *                        with imprecise steering.
 *   send-only-on-change — suppress the packet when there is no movement input
 *                        (f == 0 && s == 0). Reduces packet rate to mimic a real
 *                        vehicle at rest.
 *
 * DETECTION: apply the SAME authority to vehicle movement as to player movement
 * — ground/collision validation, per-tick speed caps appropriate to the vehicle,
 * and continuity checks.
 */
public class VehicleMove extends Module {
    public enum Mode { FLY, SPEED }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("FLY = vertical control with jump/sneak. SPEED = horizontal boost.")
        .defaultValue(Mode.FLY).build()
    );
    private final Setting<Double> horizontal = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed").description("Blocks per tick in the look direction.")
        .defaultValue(0.6).range(0.0, 5.0).sliderRange(0.0, 1.5).build()
    );
    private final Setting<Double> speedJitter = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed-jitter")
        .description("Random ±fraction applied to horizontal speed per tick. Tests variance-based vehicle speed detection.")
        .defaultValue(0.0).range(0.0, 0.3).sliderRange(0.0, 0.2).build()
    );
    private final Setting<Double> directionNoise = sgGeneral.add(new DoubleSetting.Builder()
        .name("direction-noise")
        .description("Rotate yaw by ±N degrees when computing direction. Mimics imprecise steering.")
        .defaultValue(0.0).range(0.0, 5.0).sliderRange(0.0, 3.0).build()
    );
    private final Setting<Boolean> sendOnlyOnChange = sgGeneral.add(new BoolSetting.Builder()
        .name("send-only-on-change")
        .description("Skip packet when there is no movement input (f==0 && s==0). Reduces packet noise.")
        .defaultValue(false).build()
    );
    private final Setting<Double> vertical = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed").description("Blocks per tick up/down (FLY mode, jump/sneak).")
        .defaultValue(0.4).range(0.0, 5.0).sliderRange(0.0, 1.5)
        .visible(() -> mode.get() == Mode.FLY).build()
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

    public VehicleMove() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "vehicle-move",
            "Flies/speeds the vehicle you're riding via VehicleMoveC2SPacket. Tests vehicle-movement authority.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        Entity vehicle = mc.player.getVehicle();
        if (vehicle == null) { warning("Not riding a vehicle, disabling."); toggle(); return; }

        double f = mc.player.forwardSpeed;
        double s = mc.player.sidewaysSpeed;

        if (sendOnlyOnChange.get() && f == 0 && s == 0) return;

        double vx = 0, vz = 0;
        if (f != 0 || s != 0) {
            double len = Math.sqrt(f * f + s * s);
            f /= len; s /= len;
            double noise = directionNoise.get();
            double jit = speedJitter.get() > 0 ? (Math.random() * 2 - 1) * speedJitter.get() : 0;
            double h = horizontal.get() * (1.0 + jit);
            double yaw = Math.toRadians(mc.player.getYaw()
                + (noise > 0 ? (Math.random() * 2 - 1) * noise : 0));
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            vx = (f * -sin + s * cos) * h;
            vz = (f *  cos + s * sin) * h;
        }

        double vy;
        if (mode.get() == Mode.FLY) {
            if (mc.options.jumpKey.isPressed()) vy = vertical.get();
            else if (mc.options.sneakKey.isPressed()) vy = -vertical.get();
            else vy = 0;
        } else {
            vy = vehicle.getVelocity().y;
        }

        Vec3d p = vehicle.getEntityPos();
        vehicle.setPosition(p.x + vx, p.y + vy, p.z + vz);
        vehicle.setVelocity(vx, vy, vz);
        mc.player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(vehicle));
        packetsSent++;
        obs.markSent();
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
