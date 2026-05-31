package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
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
 * ServerboundVehicleMove (VehicleMoveC2SPacket), a DIFFERENT path from player
 * movement, and is frequently validated far more loosely - or not at all. While
 * you're riding, this rewrites the vehicle's position each tick (vertical fly
 * and/or horizontal speed in your look direction) and reports it, so the server
 * sees a boat/minecart flying or moving faster than its physics allow.
 *
 * Boat-fly and boat-speed are perennial bypasses precisely because the player
 * movement checks never run on the vehicle.
 *
 * DETECTION: apply the SAME authority to vehicle movement as to player movement
 * - ground/collision validation, per-tick speed caps appropriate to the vehicle,
 * and continuity checks. Reject a vehicle position delta that exceeds the
 * vehicle's max speed or that places it in the air with no support.
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
    private final Setting<Double> vertical = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed").description("Blocks per tick up/down (FLY mode, jump/sneak).")
        .defaultValue(0.4).range(0.0, 5.0).sliderRange(0.0, 1.5)
        .visible(() -> mode.get() == Mode.FLY).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public VehicleMove() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "vehicle-move",
            "Flies/speeds the vehicle you're riding via VehicleMoveC2SPacket. Tests vehicle-movement authority.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        Entity vehicle = mc.player.getVehicle();
        if (vehicle == null) { warning("Not riding a vehicle, disabling."); toggle(); return; }

        // horizontal vector from movement input rotated by player yaw
        double f = mc.player.forwardSpeed;
        double s = mc.player.sidewaysSpeed;
        double vx = 0, vz = 0;
        if (f != 0 || s != 0) {
            double len = Math.sqrt(f * f + s * s);
            f /= len; s /= len;
            double yaw = Math.toRadians(mc.player.getYaw());
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            vx = (f * -sin + s * cos) * horizontal.get();
            vz = (f * cos + s * sin) * horizontal.get();
        }

        double vy;
        if (mode.get() == Mode.FLY) {
            if (mc.options.jumpKey.isPressed()) vy = vertical.get();
            else if (mc.options.sneakKey.isPressed()) vy = -vertical.get();
            else vy = 0;
        } else {
            vy = vehicle.getVelocity().y; // keep gravity in SPEED mode
        }

        Vec3d p = vehicle.getEntityPos();
        vehicle.setPosition(p.x + vx, p.y + vy, p.z + vz);
        vehicle.setVelocity(vx, vy, vz);
        mc.player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(vehicle));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
