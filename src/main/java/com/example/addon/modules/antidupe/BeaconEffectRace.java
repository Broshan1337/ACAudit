package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.UpdateBeaconC2SPacket;

import java.util.Optional;

/**
 * AUDIT: Beacon Effect-Confirm Race (axis 7)
 *
 * The beacon "set effects" action consumes a payment item and applies an effect.
 * Between the click that confirms the effect and the server debiting the payment,
 * there is a brief window. This module SNIPEs repeated UpdateBeacon packets at a
 * precise offset (or SWEEPs to find it) while the beacon GUI is open, testing
 * whether each is treated as a fresh, separately-charged confirmation or whether
 * the payment consumption is atomic with the effect application.
 *
 * Vulnerable server: applies/re-applies the effect per packet without re-checking
 * that the payment is still present and un-consumed -> free effects / payment dupe.
 * Hardened server: validates an open beacon menu, validates+consumes the payment
 * atomically with the single effect application, and rate-limits the packet.
 * Fix: gate on an open beacon container; consume payment and apply effect under
 * one lock; reject duplicate confirmations for the same payment.
 *
 * Open a beacon GUI with a payment item, enable. Run against your OWN server only.
 */
public class BeaconEffectRace extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final TickWindow window = new TickWindow(sgGeneral);
    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public BeaconEffectRace() {
        super(AddonTemplate.DUPE_CATEGORY, "beacon-effect-race",
            "Snipes beacon effect-confirm packets in the payment-consume window. Tests payment-vs-effect atomicity.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        window.onActivate(); obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null
            || mc.player.currentScreenHandler == mc.player.playerScreenHandler) return;
        ticksActive++;
        obs.tick();
        if (!window.armed()) window.arm();
        if (!window.shouldFire()) return;

        mc.player.networkHandler.sendPacket(new UpdateBeaconC2SPacket(Optional.empty(), Optional.empty()));
        packetsSent++;
        obs.markFired();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.survey(event.packet, l -> info("%s", l)); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
