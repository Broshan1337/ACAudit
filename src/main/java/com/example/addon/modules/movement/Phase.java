package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: Phase (noclip through blocks)
 *
 * On keypress, walks your reported position forward through whatever is in front
 * of you in a series of small position packets, then snaps the client to the far
 * side — the classic corner/wall phase. Tests whether the server validates that
 * each move originates adjacent to the last authoritative position and does not
 * pass through solid collision.
 *
 * Subtlety controls:
 *   step-variance — each step size = baseStep * (1 ± variance). Tests whether the
 *                   AC catches fixed-interval phasing or also variable-step phasing.
 *   yaw-drift     — apply a small random rotation per packet. Mimics micro input
 *                   changes during a real movement sequence. Tests whether the AC
 *                   assumes a perfectly straight path through the wall.
 *
 * Combination: Phase+AntiSetback (canonical wall-bypass combo). Phase+Blink (phase
 * during silence so the AC only sees the final position).
 *
 * DETECTION: validate move CONTINUITY against server-side collision. Ray/sweep
 * the segment between the last accepted position and the new one; if it crosses
 * solid blocks the player shouldn't pass, reject and set back.
 */
public class Phase extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance").description("Blocks to travel forward through the wall.")
        .defaultValue(2.0).range(0.5, 6.0).sliderRange(0.5, 4.0).build()
    );
    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets").description("Position packets to split the travel across.")
        .defaultValue(4).range(1, 30).sliderRange(1, 12).build()
    );
    private final Setting<Double> stepVariance = sgGeneral.add(new DoubleSetting.Builder()
        .name("step-variance")
        .description("Random ±fraction applied to each step size. Tests variable-step vs. fixed-step detection.")
        .defaultValue(0.2).range(0.0, 0.5).sliderRange(0.0, 0.4).build()
    );
    private final Setting<Double> yawDrift = sgGeneral.add(new DoubleSetting.Builder()
        .name("yaw-drift")
        .description("Random ±degrees of yaw applied per packet. Mimics micro-input changes during phasing.")
        .defaultValue(0.5).range(0.0, 5.0).sliderRange(0.0, 3.0).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Press to phase forward.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_G)).build()
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
    private boolean wasPressed = false;

    public Phase() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "phase",
            "Walks reported position forward through blocks then snaps across. Tests move-continuity / collision validation.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; wasPressed = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        double baseStep = distance.get() / packets.get();
        double variance = stepVariance.get();
        double drift = yawDrift.get();

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();

        for (int i = 0; i < packets.get(); i++) {
            double effectiveStep = variance > 0
                ? baseStep * (1.0 + (Math.random() * 2 - 1) * variance)
                : baseStep;
            double packetYaw = Math.toRadians(mc.player.getYaw()
                + (drift > 0 ? (Math.random() * 2 - 1) * drift : 0));
            x += -Math.sin(packetYaw) * effectiveStep;
            z +=  Math.cos(packetYaw) * effectiveStep;
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                x, y, z, true, false));
            packetsSent++;
        }

        mc.player.setPosition(x, y, z);
        info("Phased %.1f blocks.", distance.get());
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
