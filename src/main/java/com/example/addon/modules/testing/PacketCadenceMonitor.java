package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import java.util.HashMap;
import java.util.Map;

/**
 * AUDIT: Packet Cadence Monitor (baseline S2C traffic)
 *
 * Counts every inbound server-to-client packet by class name and reports
 * the top-N types by rate each second. This gives you a baseline of what the
 * server normally sends and reveals anomalies: spikes in entity-update packets
 * (entity tracking bug), sudden floods of chunk-data packets (region loading
 * issue), or the absence of WorldTimeUpdate (TPS near zero).
 *
 * Use it as a passive baseline before and during stress tests to see exactly
 * which packet types contribute to server-side CPU and Netty backpressure.
 */
public class PacketCadenceMonitor extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> topN = sgGeneral.add(new IntSetting.Builder()
        .name("top-n").description("Report the top N packet types each interval.")
        .defaultValue(5).range(1, 20).sliderRange(1, 10).build()
    );
    private final Setting<Integer> intervalSecs = sgGeneral.add(new IntSetting.Builder()
        .name("interval-s").description("Seconds between reports.")
        .defaultValue(5).range(1, 60).sliderRange(1, 30).build()
    );

    private final Map<String, Integer> counts = new HashMap<>();
    private long windowStart;
    private int totalIn;

    public PacketCadenceMonitor() {
        super(AddonTemplate.TESTING_CATEGORY, "packet-cadence-monitor",
            "Counts S2C packets by type and reports top-N per second. Establishes baseline server traffic patterns.");
    }

    @Override
    public void onActivate() {
        counts.clear(); windowStart = System.currentTimeMillis(); totalIn = 0;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        String name = event.packet.getClass().getSimpleName();
        counts.merge(name, 1, (a, b) -> a + b);
        totalIn++;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        long now = System.currentTimeMillis();
        long elapsed = now - windowStart;
        if (elapsed < intervalSecs.get() * 1000L) return;

        double secs = elapsed / 1000.0;
        info("=== Packet cadence (%.1fs window, %d total, %.0f/s) ===", secs, totalIn, totalIn / secs);

        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(topN.get())
            .forEach(e -> info("  %-50s %5d  (~%.1f/s)", e.getKey(), e.getValue(), e.getValue() / secs));

        counts.clear(); totalIn = 0; windowStart = now;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }
}
