package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;

/**
 * AUDIT: Server Health Monitor
 *
 * Passive, read-only estimator of server-side health from the client. Used as
 * the measurement source for SoakTest, and useful standalone.
 *
 * TPS estimate: vanilla sends WorldTimeUpdateS2CPacket every 20 server ticks.
 * The wall-clock interval between two of them reflects how long 20 server ticks
 * actually took, so tps = 20000 / intervalMs (clamped to 20). When the server
 * falls behind, the interval stretches and the estimate drops - exactly the
 * signal a soak test needs.
 *
 * Ping: the server's measured latency for us (KeepAlive round trip).
 */
public class ServerHealthMonitor extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> logEachSecond = sgGeneral.add(new BoolSetting.Builder()
        .name("log-each-second")
        .description("Print TPS/ping to chat once per second while active.")
        .defaultValue(false).build()
    );

    private long lastTimePacketMs = 0;
    private double tps = 20.0;
    private int lastLogSecond = -1;

    public ServerHealthMonitor() {
        super(AddonTemplate.TESTING_CATEGORY, "server-health-monitor",
            "Passively estimates server TPS (from time-update cadence) and ping. Measurement source for soak tests.");
    }

    @Override
    public void onActivate() {
        lastTimePacketMs = 0;
        tps = 20.0;
        lastLogSecond = -1;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof WorldTimeUpdateS2CPacket)) return;
        long now = System.currentTimeMillis();
        if (lastTimePacketMs != 0) {
            long interval = now - lastTimePacketMs;
            if (interval > 0) {
                double est = 20000.0 / interval;     // 20 ticks per packet
                tps = Math.min(20.0, est);
            }
        }
        lastTimePacketMs = now;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!logEachSecond.get()) return;
        int sec = (int) (System.currentTimeMillis() / 1000);
        if (sec != lastLogSecond) {
            lastLogSecond = sec;
            info("TPS ~%.1f | ping %dms", tps, getPing());
        }
    }

    /** Estimated server TPS, clamped to [0, 20]. */
    public double getTps() { return tps; }

    /** Server-measured latency in ms, or -1 if unavailable. */
    public int getPing() {
        if (mc.player == null || mc.getNetworkHandler() == null) return -1;
        var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? -1 : entry.getLatency();
    }

    /** True if we've seen at least one time-update interval (estimate is warm). */
    public boolean isWarm() { return lastTimePacketMs != 0; }

    /**
     * Milliseconds since the last server time-update packet, or -1 if none seen.
     * A long gap while still connected indicates a server HANG (the main thread
     * stopped ticking) rather than a clean crash or a recoverable lag spike.
     */
    public long msSinceLastSample() {
        return lastTimePacketMs == 0 ? -1 : System.currentTimeMillis() - lastTimePacketMs;
    }
}
