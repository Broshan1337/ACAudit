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
 * side - the classic corner/wall phase. It tests whether the server validates
 * that each move originates adjacent to the last authoritative position and does
 * not pass through solid collision.
 *
 * DETECTION: validate move CONTINUITY against server-side collision. Ray/sweep
 * the segment between the last accepted position and the new one; if it crosses
 * solid blocks the player shouldn't pass, reject and set back. Per-packet
 * "is the endpoint legal" is not enough - the path between packets must be legal
 * too.
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
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Press to phase forward.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_G)).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private boolean wasPressed = false;

    public Phase() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "phase",
            "Walks reported position forward through blocks then snaps across. Tests move-continuity / collision validation.");
    }

    @Override
    public void onActivate() { wasPressed = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        double yaw = Math.toRadians(mc.player.getYaw());
        double dirX = -Math.sin(yaw);
        double dirZ = Math.cos(yaw);
        double step = distance.get() / packets.get();

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        for (int i = 0; i < packets.get(); i++) {
            x += dirX * step;
            z += dirZ * step;
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                x, y, z, true, false));
        }
        mc.player.setPosition(x, y, z);
        info("Phased %.1f blocks.", distance.get());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
