package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.network.packet.c2s.play.UpdatePlayerAbilitiesC2SPacket;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Flight Claim (client-reported ability trust)
 *
 * PLATFORM: Bukkit-universal (audits the AC; the ability bit is a vanilla field).
 *
 * The client reports its OWN ability flags (flying / allowFlying) via
 * UpdatePlayerAbilitiesC2SPacket. A server or anticheat that trusts this bit lets a
 * client grant itself flight by simply claiming it. This module asserts flying=true
 * (and optionally moves upward) and grades via MovementObserver whether the server
 * corrects/kicks (it re-derives ability from authoritative state) or silently
 * accepts the claim (it trusted the client bit -- the dangerous case).
 *
 *   Patch signal (any well-implemented server/AC): never trust the client's
 *     allowFlying/flying flags; derive flight permission from authoritative state
 *     (gamemode, /fly grant) and validate vertical movement against it.
 *
 * Run on YOUR server only.
 */
public class FlightClaim extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> moveUp = sgGeneral.add(new BoolSetting.Builder()
        .name("move-up").description("Also push upward while claiming flight, to test whether the claim lets vertical movement pass.")
        .defaultValue(true).build()
    );
    private final Setting<Double> climbSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("climb-speed").description("Upward speed (b/t) while claiming flight.")
        .defaultValue(0.2).range(0.0, 1.0).sliderRange(0.0, 0.5)
        .visible(moveUp::get).build()
    );
    private final Setting<Integer> resendTicks = sgGeneral.add(new IntSetting.Builder()
        .name("resend-ticks").description("Re-assert the flying claim every N ticks.")
        .defaultValue(5).range(1, 40).sliderRange(1, 20).build()
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

    private int ticksActive = 0, timer = 0;

    public FlightClaim() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "flight-claim",
            "Asserts flying=true via UpdatePlayerAbilities and moves up. Tests whether the server/AC trusts the client-reported flight ability bit.");
    }

    @Override
    public void onActivate() { ticksActive = 0; timer = 0; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        if (timer <= 0) {
            PlayerAbilities a = new PlayerAbilities();
            a.allowFlying = true;
            a.flying = true;
            mc.player.networkHandler.sendPacket(new UpdatePlayerAbilitiesC2SPacket(a));
            timer = resendTicks.get();
            obs.markSent();
        } else timer--;

        if (moveUp.get()) {
            Vec3d v = mc.player.getVelocity();
            mc.player.setVelocity(v.x, climbSpeed.get(), v.z);
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active.", ticksActive);
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
