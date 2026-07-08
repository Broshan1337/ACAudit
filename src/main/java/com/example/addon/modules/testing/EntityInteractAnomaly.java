package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Entity-Interaction Anomaly (interaction identity / visibility / state trust)
 *
 * <p>Interaction packets carry an entity id and an action. A correct server validates that
 * the target was actually TRACKED to this client, is in reach, is alive, and that one
 * interaction of one type happens per tick. This probe attacks each of those assumptions.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code MULTI_TYPE_SAME_TICK} — send attack + interact + interactAt for the SAME
 *       crosshair target in one tick. Tests handlers that assume a single interaction type
 *       per entity per tick (double-processing, attack+use race).</li>
 *   <li>{@code NEVER_SPAWNED_ID} — attack raw entity ids that were never sent to this client
 *       (a throwaway entity with {@code setId}). If a hit LANDS, the server is resolving
 *       interactions against entities it never made visible to you — the packet-layer analogue
 *       of hitting a player through a wall / that you can't see. High-value vuln class.</li>
 *   <li>{@code VEHICLE_MOVE_NOT_RIDING} — send {@link VehicleMoveC2SPacket} while not in a
 *       vehicle. The server should reject vehicle movement with no vehicle; some derive
 *       position from it unconditionally.</li>
 *   <li>{@code INTERACT_AT_DEATH} — spray interact on the crosshair target so an interaction
 *       lands on the exact tick it dies (post-death loot / double-interact window).</li>
 * </ul>
 *
 * <p>Patch signal: resolve interaction targets only against entities currently tracked to
 * THIS player, re-check alive + reach at handle time, and collapse to one interaction per
 * entity per tick; reject vehicle-move when the player has no vehicle.
 *
 * <p>Non-op, client-side. NEVER_SPAWNED_ID and VEHICLE_MOVE_NOT_RIDING need no target;
 * the others need an entity under your crosshair. Own server only.
 */
public class EntityInteractAnomaly extends Module {
    public enum Mode { MULTI_TYPE_SAME_TICK, NEVER_SPAWNED_ID, VEHICLE_MOVE_NOT_RIDING, INTERACT_AT_DEATH }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which entity-interaction assumption to probe.")
        .defaultValue(Mode.NEVER_SPAWNED_ID).build());
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("actions-per-tick").description("Interactions (or ids) attempted per active tick.")
        .defaultValue(10).range(1, 200).sliderRange(1, 50).build());
    private final Setting<Integer> idStart = sgGeneral.add(new IntSetting.Builder()
        .name("id-start").description("NEVER_SPAWNED_ID: first raw entity id to probe (scans upward by actions-per-tick each tick).")
        .defaultValue(1).range(0, 1_000_000).sliderRange(1, 5000)
        .visible(() -> mode.get() == Mode.NEVER_SPAWNED_ID).build());
    private final Setting<Boolean> sneak = sgGeneral.add(new BoolSetting.Builder()
        .name("sneaking-flag").description("Value of the 'is sneaking' flag on interaction packets.")
        .defaultValue(false).build());

    private int cursor = 0, packetsSent = 0;
    private ArmorStandEntity marker;

    public EntityInteractAnomaly() {
        super(AddonTemplate.TESTING_CATEGORY, "entity-interact-anomaly",
            "Probes interaction target validation: multiple types/tick, hitting never-spawned entity ids, vehicle-move with no vehicle, interact-on-death.");
    }

    @Override
    public void onActivate() {
        cursor = idStart.get();
        packetsSent = 0;
        // A throwaway entity we never add to the world — its id is overwritten per attack so
        // we can address arbitrary raw ids through the id-taking interact factories.
        marker = mc.world != null ? new ArmorStandEntity(mc.world, 0, 0, 0) : null;
        info("Mode %s — watch: %s", mode.get(), watchHint());
    }

    private String watchHint() {
        return switch (mode.get()) {
            case MULTI_TYPE_SAME_TICK -> "double damage/interact processing on one entity in one tick.";
            case NEVER_SPAWNED_ID -> "ANY hit landing (damage dealt, mob reacts, plugin fires) on an id you were never sent = server not checking tracking/visibility (VULNERABLE).";
            case VEHICLE_MOVE_NOT_RIDING -> "the server accepting a vehicle position with no vehicle (teleport/position desync).";
            case INTERACT_AT_DEATH -> "a second interaction/loot resolving after the entity is already dead.";
        };
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        var nh = mc.player.networkHandler;
        switch (mode.get()) {
            case NEVER_SPAWNED_ID -> {
                if (marker == null) return;
                for (int i = 0; i < perTick.get(); i++) {
                    marker.setId(cursor++);
                    nh.sendPacket(PlayerInteractEntityC2SPacket.attack(marker, sneak.get()));
                    packetsSent++;
                }
            }
            case VEHICLE_MOVE_NOT_RIDING -> {
                if (mc.player.getVehicle() != null) {
                    warning("You ARE in a vehicle — dismount so this probe is meaningful. Skipping.");
                    return;
                }
                Vec3d p = mc.player.getEntityPos();
                for (int i = 0; i < perTick.get(); i++) {
                    nh.sendPacket(new VehicleMoveC2SPacket(
                        new Vec3d(p.x, p.y, p.z), mc.player.getYaw(), mc.player.getPitch(), true));
                    packetsSent++;
                }
            }
            case MULTI_TYPE_SAME_TICK, INTERACT_AT_DEATH -> {
                Entity target = crosshairEntity();
                if (target == null) return;
                for (int i = 0; i < perTick.get(); i++) {
                    if (mode.get() == Mode.MULTI_TYPE_SAME_TICK) {
                        nh.sendPacket(PlayerInteractEntityC2SPacket.attack(target, sneak.get()));
                        nh.sendPacket(PlayerInteractEntityC2SPacket.interact(target, sneak.get(), Hand.MAIN_HAND));
                        nh.sendPacket(PlayerInteractEntityC2SPacket.interactAt(target, sneak.get(), Hand.MAIN_HAND,
                            target.getEntityPos().subtract(mc.player.getEntityPos())));
                        packetsSent += 3;
                    } else {
                        nh.sendPacket(PlayerInteractEntityC2SPacket.interact(target, sneak.get(), Hand.MAIN_HAND));
                        nh.sendPacket(PlayerInteractEntityC2SPacket.attack(target, sneak.get()));
                        packetsSent += 2;
                    }
                }
            }
        }
    }

    private Entity crosshairEntity() {
        Entity e = (mc.crosshairTarget instanceof EntityHitResult ehr) ? ehr.getEntity() : null;
        if (e == null) info("No entity under crosshair — aim at a target for this mode.");
        return e;
    }

    @Override
    public void onDeactivate() {
        info("Summary: %d interaction packets sent.", packetsSent);
        if (mode.get() == Mode.NEVER_SPAWNED_ID)
            info("Probed raw entity ids %d..%d.", idStart.get(), Math.max(idStart.get(), cursor - 1));
        marker = null;
    }
}
