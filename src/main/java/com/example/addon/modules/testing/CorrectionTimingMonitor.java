package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;

/**
 * AUDIT: Correction Timing Monitor (rubber-band latency)
 *
 * Measures the round-trip time from sending an outbound movement packet to
 * receiving a server correction (PlayerPositionLookS2C — the "rubber-band").
 * This is the AC's RESPONSE LATENCY: how many milliseconds (or ticks) between
 * a detectable cheat move and the setback arriving.
 *
 * A long lag between the illegal move and the correction is a "grace window" that
 * cheats exploit — they get several ticks of benefit before the server acts. A
 * very short lag means the AC is synchronous and checks each movement packet.
 *
 * Reports: min/max/avg correction RTT in ms and in ticks (÷50). Run alongside a
 * movement module that triggers setbacks.
 */
public class CorrectionTimingMonitor extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> verbose = sgGeneral.add(new BoolSetting.Builder()
        .name("verbose").description("Print RTT for every individual correction.")
        .defaultValue(true).build()
    );

    // Ring buffer of the last outbound movement packet timestamps
    private static final int BUF = 64;
    private final long[] sentAt = new long[BUF];
    private int writeIdx = 0;
    private long lastSentMs = -1;

    private long minRtt = Long.MAX_VALUE, maxRtt = 0, sumRtt = 0;
    private int count = 0;

    public CorrectionTimingMonitor() {
        super(AddonTemplate.TESTING_CATEGORY, "correction-timing-monitor",
            "Measures round-trip time from sending movement to receiving a setback. Reports AC response latency.");
    }

    @Override
    public void onActivate() {
        writeIdx = 0; lastSentMs = -1;
        minRtt = Long.MAX_VALUE; maxRtt = 0; sumRtt = 0; count = 0;
        for (int i = 0; i < BUF; i++) sentAt[i] = -1;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerMoveC2SPacket)) return;
        long now = System.currentTimeMillis();
        sentAt[writeIdx % BUF] = now;
        writeIdx++;
        lastSentMs = now;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket)) return;
        if (lastSentMs < 0) return;
        // Best estimate: correct against the most recently sent movement packet
        long rtt = System.currentTimeMillis() - lastSentMs;
        count++;
        sumRtt += rtt;
        if (rtt < minRtt) minRtt = rtt;
        if (rtt > maxRtt) maxRtt = rtt;
        if (verbose.get()) {
            info("Correction RTT: %dms (~%.1f ticks)", rtt, rtt / 50.0);
        }
    }

    @Override
    public void onDeactivate() {
        if (count == 0) { info("No corrections observed."); return; }
        info("Correction RTT — min: %dms  max: %dms  avg: %dms  (~%.1f ticks avg)  n=%d",
            minRtt, maxRtt, sumRtt / count, (sumRtt / count) / 50.0, count);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }
}
