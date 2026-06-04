package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: Entity Tracker Thrash
 *
 * Rapidly ping-pongs reported position in and out of the entity-TRACKING range
 * (distinct from chunk-border-stress, which crosses the 16-block chunk grid).
 * Each time the player crosses an entity's tracking threshold, the server must
 * add or remove that player from the entity's tracker: spawn/destroy packets,
 * tracked-data syncs, and viewer-set updates — for every nearby entity, every
 * crossing. Oscillating across the boundary forces continuous add/remove churn
 * on the entity-tracking system.
 *
 * What a vulnerable server does: processes every tracking transition the moment
 * the position packet arrives, so N crossings/tick = N tracker rebuilds/tick.
 * What a hardened server does: rate-limits position-driven tracking updates
 * (debounced to the tick), and rejects sub-physics-speed jumps across large
 * distances before they drive tracker work.
 * Fix: debounce tracking updates to once per player per tick from the
 * authoritative position; cap how far a single accepted move may jump.
 *
 * Best run where movement isn't hard-capped (creative flight / no speed limit),
 * or it doubles as a setback-storm test. Stand near entities/players.
 * Run against your OWN local server only.
 */
public class TrackerThrash extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> crossingsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("crossings-per-tick").description("Tracking-range crossings per tick.")
        .defaultValue(20).range(1, 500).sliderRange(1, 100).build()
    );
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("Blocks to jump out and back (should exceed the server's entity tracking range, ~48-128).")
        .defaultValue(80.0).range(8.0, 512.0).sliderRange(32.0, 256.0).build()
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
    private boolean far = false;

    public TrackerThrash() {
        super(AddonTemplate.CRASH_CATEGORY, "tracker-thrash",
            "Ping-pongs position across entity tracking range. Tests entity-tracker add/remove debouncing.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; far = false;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;

        double baseX = mc.player.getX(), y = mc.player.getY(), baseZ = mc.player.getZ();
        for (int i = 0; i < crossingsPerTick.get(); i++) {
            far = !far;
            double x = far ? baseX + range.get() : baseX;
            double z = far ? baseZ + range.get() : baseZ;
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, true, false));
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
