package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
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
 * Subtlety controls:
 *   arc-noise      — ±random variation on each arc Y offset. Tests whether the
 *                    AC checks the exact known jump-arc values or just the overall
 *                    trajectory shape.
 *   cooldown-ticks — minimum ticks between step attempts. Tests "stepped twice in
 *                    the same physical climb" detection.
 *
 * Combination: InstantStep+AntiSetback (step, then block the setback).
 * InstantStep+PacketStep (combine arc mimicry with raw Y teleport).
 *
 * Ported from LiquidBounce Step (Instant mode).
 */
public class InstantStep extends Module {
    private static final double[] JUMP_ARC = {
        0.42, 0.7531999805212, 1.00133597911215, 1.166109260938214, 1.24918707874468
    };

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height").description("Max step-up height to attempt.")
        .defaultValue(1.0).range(0.6, 3.0).sliderRange(0.6, 2.0).build()
    );
    private final Setting<Double> arcNoise = sgGeneral.add(new DoubleSetting.Builder()
        .name("arc-noise")
        .description("Random ±variation applied to each arc Y offset. Tests arc-shape vs. arc-value detection.")
        .defaultValue(0.003).range(0.0, 0.01).sliderRange(0.0, 0.008).build()
    );
    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ticks").description("Minimum ticks between step attempts. Tests rapid-step detection.")
        .defaultValue(3).range(0, 10).sliderRange(0, 8).build()
    );

    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private int cooldown = 0;

    public InstantStep() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "instant-step",
            "Instantly steps up a full block via a jump-arc packet sequence. Tests step-height / vertical-teleport checks.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; cooldown = 0; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;
        obs.tick();

        if (cooldown > 0) { cooldown--; return; }
        if (!mc.player.isOnGround() || !mc.player.horizontalCollision) return;
        if (mc.player.forwardSpeed == 0 && mc.player.sidewaysSpeed == 0) return;

        double baseY = mc.player.getY();
        double x = mc.player.getX();
        double z = mc.player.getZ();
        double noise = arcNoise.get();

        for (double offset : JUMP_ARC) {
            if (offset > height.get()) break;
            double jit = noise > 0 ? (Math.random() * 2 - 1) * noise : 0;
            double y = baseY + offset + jit;
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, false));
            packetsSent++;
        }

        double topY = baseY + height.get();
        mc.player.setPosition(x, topY, z);
        mc.player.setVelocity(new Vec3d(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z));
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, topY, z, true, true));
        packetsSent++;
        obs.markSent();

        cooldown = cooldownTicks.get();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
