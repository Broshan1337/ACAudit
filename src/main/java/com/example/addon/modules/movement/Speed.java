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
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Speed (Strafe)
 *
 * Overrides horizontal velocity to a fixed speed in the player's input
 * direction. Vanilla ground speed is ~0.21 b/t walking, ~0.28 sprinting; any
 * sustained value above that is the most common horizontal-speed flag.
 *
 * Re-derived from the strafe math LiquidBounce's speed modes use (input vector
 * rotated by yaw, scaled to target speed).
 */
public class Speed extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("Blocks per tick (vanilla sprint ~0.28).")
        .defaultValue(0.5).range(0.1, 5.0).sliderRange(0.21, 1.0).build()
    );
    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-ground").description("Only boost while on the ground.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public Speed() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "speed",
            "Boosts horizontal velocity in the input direction. Tests horizontal-speed detection.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (onlyOnGround.get() && !mc.player.isOnGround()) return;

        float forward  = mc.player.forwardSpeed;
        float sideways = mc.player.sidewaysSpeed;
        if (forward == 0 && sideways == 0) return; // not actively moving

        // normalise the input vector so diagonals aren't faster
        double len = Math.sqrt(forward * forward + sideways * sideways);
        double f = forward / len;
        double s = sideways / len;

        double yaw = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        double velX = (f * -sin + s * cos) * speed.get();
        double velZ = (f * cos + s * sin) * speed.get();

        Vec3d v = mc.player.getVelocity();
        mc.player.setVelocity(velX, v.y, velZ);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
