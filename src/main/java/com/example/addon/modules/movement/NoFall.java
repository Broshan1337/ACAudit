package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: NoFall (spoof-ground)
 *
 * While falling far enough to take damage, periodically sends an onGround=true
 * movement packet without actually touching ground. A server that applies fall
 * damage from client-reported ground contact resets the fall accumulator and
 * deals no damage.
 *
 * DETECTION: never compute fall damage from a client onGround flag. Track the
 * highest Y reached and the landing Y server-side from authoritative position,
 * and apply damage from that delta. An onGround=true with no solid block under
 * the reported position is itself a flag.
 */
public class NoFall extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> minFall = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-fall-distance")
        .description("Only spoof once fall distance exceeds this (vanilla damage starts >3).")
        .defaultValue(2.5).range(0.0, 10.0).sliderRange(0.0, 5.0).build()
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

    public NoFall() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-no-fall",
            "Spoofs onGround while falling to cancel fall damage. Tests server-side fall-damage computation.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        if (mc.player.isOnGround()) return;
        if (mc.player.fallDistance <= minFall.get()) return;
        // velocity must be downward (we're actually falling)
        if (mc.player.getVelocity().y >= 0) return;

        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            mc.player.getX(), mc.player.getY(), mc.player.getZ(), true, mc.player.horizontalCollision));
            packetsSent++;
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
