package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;

/**
 * AUDIT: AC Check-Rate Monitor
 *
 * Counts incoming setback packets (PlayerPositionLookS2C — the server's "you are
 * not where you say you are" correction) per second, split by whether the player
 * was moving or standing still. This fingerprints the anti-cheat's check cadence:
 *   - Setbacks while still  -> AC is checking idle players (motion-independent AC)
 *   - Setbacks while moving -> AC responds to movement events (event-driven AC)
 *   - Setback rate vs. speed -> reveals the AC's threshold calibration
 *
 * Run alongside a movement module; the setback rate tells you whether it was
 * detected and how quickly.
 */
public class CheckRateMonitor extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> verbose = sgGeneral.add(new BoolSetting.Builder()
        .name("verbose").description("Print each setback individually in addition to the per-second summary.")
        .defaultValue(false).build()
    );
    private final Setting<Integer> reportInterval = sgGeneral.add(new IntSetting.Builder()
        .name("report-interval-s").description("Seconds between summary lines.")
        .defaultValue(1).range(1, 30).sliderRange(1, 10).build()
    );

    private int setbacksMoving, setbacksStill;
    private int reportMoving, reportStill;
    private long windowStart;
    private int totalSetbacks;

    public CheckRateMonitor() {
        super(AddonTemplate.TESTING_CATEGORY, "check-rate-monitor",
            "Counts server setback packets per second, split by moving vs. still. Fingerprints AC check cadence.");
    }

    @Override
    public void onActivate() {
        setbacksMoving = setbacksStill = reportMoving = reportStill = totalSetbacks = 0;
        windowStart = System.currentTimeMillis();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket)) return;
        totalSetbacks++;
        if (mc.player != null && mc.player.getVelocity().horizontalLengthSquared() > 0.001) {
            setbacksMoving++;
        } else {
            setbacksStill++;
        }
        if (verbose.get()) info("Setback #%d", totalSetbacks);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        long now = System.currentTimeMillis();
        if (now - windowStart < reportInterval.get() * 1000L) return;
        info("Setbacks/s → moving: %d  still: %d  (total: %d)",
            setbacksMoving, setbacksStill, totalSetbacks);
        reportMoving += setbacksMoving;
        reportStill += setbacksStill;
        setbacksMoving = setbacksStill = 0;
        windowStart = now;
    }

    @Override
    public void onDeactivate() {
        info("Session total → moving: %d  still: %d  total: %d",
            reportMoving, reportStill, totalSetbacks);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }
}
