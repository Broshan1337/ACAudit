package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Vehicle Sim-Gap (vehicle-specific prediction-gap probe)
 *
 * Vehicle movement is the least-mature path in most anticheats: it arrives on a
 * separate packet (VehicleMoveC2SPacket), its physics (boats on ice, slime, in
 * water) are special-cased, and passenger position is derived from the vehicle
 * rather than sent directly — three things a simulation must model as carefully as
 * on-foot movement but frequently does not. This module probes the gaps the basic
 * vehicle-move module does not:
 *
 *   ICE_OVERSPEED    — while riding (ideally a boat on ice), add a small delta on
 *                      top of the already-high legal ice-boat speed — a vehicle
 *                      uncertainty-spend that hides inside the loosest vehicle case.
 *   PASSENGER_DESYNC — report the vehicle at one position while reporting the
 *                      player (passenger) at a slightly different offset the same
 *                      tick, testing whether the AC reconciles passenger position
 *                      with vehicle position or trusts each path separately.
 *
 *   What it exploits: vehicle movement validated more loosely than player movement,
 *     and passenger position trusted independently of the vehicle.
 *   Patch signal (any well-implemented AC): apply the SAME authority to vehicle
 *     movement as to player movement — per-vehicle speed caps appropriate to the
 *     surface, collision/continuity checks, and reconciliation of passenger
 *     position against the vehicle it rides. Server corrections arrive as
 *     VehicleMoveS2CPacket; this module counts them.
 *
 * Requires riding a vehicle. Run on YOUR server only.
 */
public class VehicleSimGap extends Module {
    public enum Mode { ICE_OVERSPEED, PASSENGER_DESYNC }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which vehicle gap to probe.")
        .defaultValue(Mode.ICE_OVERSPEED).build()
    );
    private final Setting<Double> spendDelta = sgGeneral.add(new DoubleSetting.Builder()
        .name("spend-delta").description("Extra horizontal speed (b/t) added to the vehicle (ICE_OVERSPEED).")
        .defaultValue(0.1).range(0.0, 1.0).sliderRange(0.0, 0.4)
        .visible(() -> mode.get() == Mode.ICE_OVERSPEED).build()
    );
    private final Setting<Double> desyncOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("desync-offset").description("How far (blocks) to offset the reported passenger position from the vehicle (PASSENGER_DESYNC).")
        .defaultValue(0.4).range(0.0, 3.0).sliderRange(0.0, 1.5)
        .visible(() -> mode.get() == Mode.PASSENGER_DESYNC).build()
    );

    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0, vehicleCorrections = 0;

    public VehicleSimGap() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "vehicle-sim-gap",
            "Probes vehicle-specific prediction gaps (ice overspeed, passenger desync). Tests whether vehicle movement gets the same authority as player movement.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; vehicleCorrections = 0; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        Entity vehicle = mc.player.getVehicle();
        if (vehicle == null) { warning("Not riding a vehicle, disabling."); toggle(); return; }

        double yaw = Math.toRadians(mc.player.getYaw());
        double dirX = -Math.sin(yaw), dirZ = Math.cos(yaw);
        Vec3d p = vehicle.getEntityPos();

        switch (mode.get()) {
            case ICE_OVERSPEED -> {
                double dx = dirX * spendDelta.get(), dz = dirZ * spendDelta.get();
                vehicle.setPosition(p.x + dx, p.y, p.z + dz);
                vehicle.setVelocity(vehicle.getVelocity().x + dx, vehicle.getVelocity().y, vehicle.getVelocity().z + dz);
                mc.player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(vehicle));
                packetsSent++;
            }
            case PASSENGER_DESYNC -> {
                // Report the vehicle where it is, but the passenger at an offset, same tick.
                mc.player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(vehicle));
                double off = desyncOffset.get();
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                    p.x + dirX * off, p.y, p.z + dirZ * off, false, false));
                packetsSent += 2;
            }
        }
        obs.markSent();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets, %d vehicle corrections.", ticksActive, packetsSent, vehicleCorrections);
            if (vehicleCorrections == 0 && packetsSent > 0)
                info("→ no VehicleMoveS2C corrections: vehicle movement was not validated like player movement. INVESTIGATE.");
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.onReceive(event.packet);
        if (event.packet instanceof VehicleMoveS2CPacket) vehicleCorrections++;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
