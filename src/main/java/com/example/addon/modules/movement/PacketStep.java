package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: Packet Step (bare Y-teleport without a jump arc)
 *
 * When the jump key is pressed, sends N rising position packets claiming to
 * step straight upward at stepHeight blocks per packet — without the curved
 * Y trajectory a vanilla jump produces. Tests path-continuity validation:
 * can the server distinguish a legitimate step-up (follows a plausible jump
 * arc from the ground) from a raw vertical teleport (Y jumps discontinuously)?
 *
 * Vanilla step height is 0.6 blocks; sending 1.0+ blocks without the arc
 * packets that precede and follow a jump is the packet-step cheat. An AC
 * that only checks the final Y delta and not the sequence of intermediate
 * positions will miss it.
 *
 * DETECTION: validate that each new Y value is reachable from the previous
 * one via the player's current vertical velocity and gravity; a jump from
 * Y=0 to Y=1 in one packet with no preceding upward velocity is illegal.
 * Track velocity continuity across consecutive position packets.
 */
public class PacketStep extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> stepHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("step-height").description("Y delta to claim each step packet.")
        .defaultValue(1.0).range(0.1, 5.0).sliderRange(0.1, 3.0).build()
    );
    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets").description("Position packets to send per key press.")
        .defaultValue(3).range(1, 20).sliderRange(1, 10).build()
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

    public PacketStep() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "packet-step",
            "Spoofs Y position upward without a jump arc. Tests path-continuity validation.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;
        if (!mc.options.jumpKey.isPressed()) return;
        double y = mc.player.getY();
        for (int i = 0; i < packets.get(); i++) {
            y += stepHeight.get();
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX(), y, mc.player.getZ(), true, false));
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
