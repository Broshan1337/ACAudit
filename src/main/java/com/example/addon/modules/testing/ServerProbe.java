package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;

/**
 * AUDIT: Server Probe
 *
 * Read-only diagnostic of how the server is behaving and responding to you:
 *   - TPS estimate (WorldTimeUpdate cadence)
 *   - ping (server-measured latency)
 *   - setbacks/sec (incoming position corrections - how often the server is
 *     pulling you back, i.e. how active its movement enforcement is)
 *   - inbound / outbound packets per second
 *   - last disconnect reason
 *
 * Prints a one-line status each second. Useful on its own and as context while
 * running the abuse/lag modules - you can watch setbacks spike when a movement
 * test trips the AC, or packet rates climb under a flood.
 */
public class ServerProbe extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> log = sgGeneral.add(new BoolSetting.Builder()
        .name("log-each-second").description("Print the status line to chat once per second.")
        .defaultValue(true).build()
    );

    private long lastTimePacketMs = 0;
    private double tps = 20.0;
    private int inThisSec, outThisSec, setbacksThisSec;
    private int inRate, outRate, setbackRate;
    private int lastSecond = -1;

    public ServerProbe() {
        super(AddonTemplate.TESTING_CATEGORY, "server-probe",
            "Read-only diagnostic: TPS, ping, setbacks/sec, packet rates, last kick reason. Watch how the server responds.");
    }

    @Override
    public void onActivate() {
        lastTimePacketMs = 0; tps = 20.0;
        inThisSec = outThisSec = setbacksThisSec = 0;
        inRate = outRate = setbackRate = 0;
        lastSecond = -1;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        inThisSec++;
        if (event.packet instanceof WorldTimeUpdateS2CPacket) {
            long now = System.currentTimeMillis();
            if (lastTimePacketMs != 0) {
                long interval = now - lastTimePacketMs;
                if (interval > 0) tps = Math.min(20.0, 20000.0 / interval);
            }
            lastTimePacketMs = now;
        } else if (event.packet instanceof PlayerPositionLookS2CPacket) {
            setbacksThisSec++;
        } else if (event.packet instanceof DisconnectS2CPacket dc) {
            warning("Disconnect: %s", dc.reason().getString());
        }
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) { outThisSec++; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        int sec = (int) (System.currentTimeMillis() / 1000);
        if (sec == lastSecond) return;
        lastSecond = sec;

        inRate = inThisSec; outRate = outThisSec; setbackRate = setbacksThisSec;
        inThisSec = outThisSec = setbacksThisSec = 0;

        if (log.get()) {
            info("TPS ~%.1f | ping %dms | setbacks/s %d | in/s %d | out/s %d",
                tps, getPing(), setbackRate, inRate, outRate);
        }
    }

    private int getPing() {
        if (mc.player == null || mc.getNetworkHandler() == null) return -1;
        var e = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return e == null ? -1 : e.getLatency();
    }

    public double getTps() { return tps; }
    public int getSetbackRate() { return setbackRate; }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }
}
