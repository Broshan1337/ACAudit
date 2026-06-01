package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Lag Profiler (breaking-point finder)
 *
 * Ramps a single packet vector's rate over time and watches server TPS, then
 * reports the rate at which TPS first fell below the floor (or you were kicked)
 * - "what rate of X actually lags the server?" Gives you a concrete number to
 * set rate limits against, instead of guessing.
 *
 * Run against your OWN local server. Reads TPS from ServerHealthMonitor
 * (auto-enabled). Getting kicked at a given rate is also a valid breaking point.
 */
public class LagProfiler extends Module {
    public enum Vector { MOVE, SWING, OFFHAND_SWAP, SPRINT_TOGGLE, HELD_SLOT, CLIENT_SETTINGS }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Vector> vector = sgGeneral.add(new EnumSetting.Builder<Vector>()
        .name("vector").description("Which packet to ramp.")
        .defaultValue(Vector.MOVE).build()
    );
    private final Setting<Integer> startRate = sgGeneral.add(new IntSetting.Builder()
        .name("start-rate").description("Packets/tick to begin at.")
        .defaultValue(50).range(1, 5000).sliderRange(1, 500).build()
    );
    private final Setting<Integer> step = sgGeneral.add(new IntSetting.Builder()
        .name("step").description("Rate increase each interval.")
        .defaultValue(50).range(1, 5000).sliderRange(1, 500).build()
    );
    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval-seconds").description("Seconds to hold each rate before ramping.")
        .defaultValue(4).range(1, 30).sliderRange(2, 15).build()
    );
    private final Setting<Double> tpsFloor = sgGeneral.add(new DoubleSetting.Builder()
        .name("tps-floor").description("Breaking point = min TPS this interval drops below this.")
        .defaultValue(18.0).range(0.0, 20.0).sliderRange(5.0, 20.0).build()
    );
    private final Setting<Integer> maxRate = sgGeneral.add(new IntSetting.Builder()
        .name("max-rate").description("Give up (PASS) if this rate is reached without lag.")
        .defaultValue(2000).range(1, 50000).sliderRange(100, 10000).build()
    );

    private ServerHealthMonitor health;
    private int rate;
    private long intervalStart;
    private double minThisInterval;
    private boolean sprintState;
    private int slotCounter;

    public LagProfiler() {
        super(AddonTemplate.TESTING_CATEGORY, "lag-profiler",
            "Ramps a packet vector's rate and reports the rate that first drops TPS below the floor. Finds the breaking point.");
    }

    @Override
    public void onActivate() {
        health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && !health.isActive()) health.toggle();
        rate = startRate.get();
        intervalStart = System.currentTimeMillis();
        minThisInterval = 20.0;
        info("Lag profiler: %s, starting %d/tick, +%d every %ds.", vector.get(), rate, step.get(), interval.get());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || health == null) return;

        // apply current load
        for (int i = 0; i < rate; i++) sendOne();

        if (health.isWarm()) minThisInterval = Math.min(minThisInterval, health.getTps());

        if (System.currentTimeMillis() - intervalStart >= interval.get() * 1000L) {
            if (minThisInterval < tpsFloor.get()) {
                warning("Breaking point: %s at ~%d/tick dropped TPS to %.1f (floor %.1f).",
                    vector.get(), rate, minThisInterval, tpsFloor.get());
                toggle();
                return;
            }
            info("  %d/tick held: min TPS %.1f", rate, minThisInterval);
            rate += step.get();
            minThisInterval = 20.0;
            intervalStart = System.currentTimeMillis();
            if (rate > maxRate.get()) {
                info("Reached max-rate %d without dropping below floor — server held.", maxRate.get());
                toggle();
            }
        }
    }

    private void sendOne() {
        switch (vector.get()) {
            case MOVE -> mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), true, false));
            case SWING -> mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            case OFFHAND_SWAP -> mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            case SPRINT_TOGGLE -> {
                sprintState = !sprintState;
                mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player,
                    sprintState ? ClientCommandC2SPacket.Mode.START_SPRINTING
                                : ClientCommandC2SPacket.Mode.STOP_SPRINTING));
            }
            case HELD_SLOT -> {
                slotCounter = (slotCounter + 1) % 9;
                mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slotCounter));
            }
            case CLIENT_SETTINGS -> mc.player.networkHandler.sendPacket(
                new ClientOptionsC2SPacket(SyncedClientOptions.createDefault()));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        warning("Disconnected at ~%d/tick of %s — that's the breaking point.", rate, vector.get());
        if (isActive()) toggle();
    }
}
