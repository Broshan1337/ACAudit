package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: InstantStep
 *
 * When the player walks into a block one step too tall to climb (and is on the
 * ground), this teleports them onto it within a couple of ticks by sending a
 * short rising sequence of position packets - instead of the vanilla smooth
 * 0.6-block auto-step. Tests step-height limits and vertical-teleport checks.
 *
 * Ported from LiquidBounce Step (Instant mode): it replays a jump-arc's Y
 * offsets as move packets so the climb looks like a fast jump rather than a
 * raw teleport, which is what lets it slip past naive checks.
 */
public class InstantStep extends Module {
    // Y offsets of a natural jump arc (LiquidBounce jumpOrder), used to make the
    // instant climb resemble a jump on the wire.
    private static final double[] JUMP_ARC = {
        0.42, 0.7531999805212, 1.00133597911215, 1.166109260938214, 1.24918707874468
    };

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height").description("Max step-up height to attempt.")
        .defaultValue(1.0).range(0.6, 3.0).sliderRange(0.6, 2.0).build()
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

    public InstantStep() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "instant-step",
            "Instantly steps up a full block via a jump-arc packet sequence. Tests step-height / vertical-teleport checks.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;
        // Only when pressed against a block while grounded and actually moving.
        if (!mc.player.isOnGround() || !mc.player.horizontalCollision) return;
        if (mc.player.forwardSpeed == 0 && mc.player.sidewaysSpeed == 0) return;

        double baseY = mc.player.getY();
        double x = mc.player.getX();
        double z = mc.player.getZ();

        for (double offset : JUMP_ARC) {
            if (offset > height.get()) break;
            double y = baseY + offset;
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, false));
            packetsSent++;
        }

        // Settle the client on top of the block.
        double topY = baseY + height.get();
        mc.player.setPosition(x, topY, z);
        mc.player.setVelocity(new Vec3d(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z));
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, topY, z, true, true));
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
