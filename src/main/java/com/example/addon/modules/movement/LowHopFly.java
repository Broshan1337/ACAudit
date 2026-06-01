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
 * AUDIT: Low-Hop Fly (onGround=true while airborne)
 *
 * Locks the player's Y at a fixed height above ground (default 0.42 blocks,
 * mimicking the peak of a vanilla jump) and reports onGround=true each tick.
 * This is the lowest-profile fly technique: the per-tick Y delta is zero, the
 * reported flag claims the player is grounded, and the horizontal speed is
 * unchanged — defeating ACs that flag only large Y deltas or onGround=false
 * while flying.
 *
 * DETECTION: onGround=true is only valid when there is a solid support block
 * at or immediately below the reported Y. The server must raycast the block
 * column below the claimed position; an onGround claim with no solid block
 * underneath is a flag regardless of per-tick delta or velocity values.
 * Never trust the client's ground flag — re-derive it server-side.
 */
public class LowHopFly extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> hoverHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("hover-height").description("Blocks above ground to hover at.")
        .defaultValue(0.42).range(0.1, 2.0).sliderRange(0.1, 1.0).build()
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

    private double targetY = -1;

    public LowHopFly() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "low-hop-fly",
            "Hovers with onGround=true. Tests server-side ground validation.");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) targetY = mc.player.getY() + hoverHeight.get();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;
        mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);
        mc.player.setPosition(mc.player.getX(), targetY, mc.player.getZ());
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            mc.player.getX(), targetY, mc.player.getZ(), true, mc.player.horizontalCollision));
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
