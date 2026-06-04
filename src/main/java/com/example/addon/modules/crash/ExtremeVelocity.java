package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: Extreme Velocity (supersonic chunk-load flooding)
 *
 * Reports position packets claiming to move hundreds of thousands of blocks
 * per tick. Two distinct failure modes:
 *
 *   1. Chunk-load flooding: the server loads chunks along the claimed path,
 *      potentially triggering block-physics, spawn-tracking, and lighting
 *      updates for thousands of chunks that the client will never actually
 *      visit. Each packet potentially kicks off enormous background work.
 *
 *   2. Speed-cap bypass: a server that enforces speed limits only on legal
 *      movements (e.g. < 100 b/t) may still process extreme-velocity moves
 *      by setbacking them — at a cost per setback — rather than flat
 *      rejecting at decode time.
 *
 * Patch signal: reject position packets whose delta from the last accepted
 * position exceeds a configurable hard cap (e.g. 100 b/t) at decode time,
 * without loading any chunks or computing any physics for the invalid claim.
 *
 * Run against your OWN local server only.
 */
public class ExtremeVelocity extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> distancePerTick = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance-per-tick").description("Blocks to claim to move per tick.")
        .defaultValue(1000.0).range(10.0, 1_000_000.0).sliderRange(10.0, 10_000.0).build()
    );
    private final Setting<Integer> packetsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Position packets per tick.")
        .defaultValue(1).range(1, 20).sliderRange(1, 10).build()
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

    private double accumX = 0;

    public ExtremeVelocity() {
        super(AddonTemplate.CRASH_CATEGORY, "extreme-velocity",
            "Claims supersonic movement per tick. Tests chunk-load flooding and speed-cap enforcement.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
        if (mc.player != null) accumX = mc.player.getX();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;
        double step = distancePerTick.get() / packetsPerTick.get();
        double y = mc.player.getY(), z = mc.player.getZ();
        for (int i = 0; i < packetsPerTick.get(); i++) {
            accumX += step;
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                accumX, y, z, true, false));
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
