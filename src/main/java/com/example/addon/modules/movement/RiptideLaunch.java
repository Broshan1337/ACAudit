package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Riptide Launch (context-spoofed)
 *
 * A riptide trident grants a large velocity burst along the look vector - but
 * only when the player is in water or in rain. ACs commonly whitelist riptide
 * velocity too broadly, so a cheat can claim "riptide" to justify a big launch
 * while dry on open ground. On keypress this imparts a riptide-magnitude impulse
 * regardless of water/rain, exactly the situation a context-aware check must
 * reject.
 *
 * DETECTION: when allowing a riptide velocity spike, the server must verify the
 * PRECONDITION it itself observed - the player was in water or exposed to rain,
 * and is holding a Riptide trident that was actually released. A large impulse
 * with no valid riptide context is a flag, not an exemption.
 */
public class RiptideLaunch extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> power = sgGeneral.add(new DoubleSetting.Builder()
        .name("power").description("Launch magnitude in blocks per tick (Riptide III ~ 1.5).")
        .defaultValue(1.5).range(0.5, 10.0).sliderRange(0.5, 4.0).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Press to launch.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_X)).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private boolean wasPressed = false;

    public RiptideLaunch() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "riptide-launch",
            "Imparts a riptide-magnitude launch with no water/rain context. Tests riptide precondition validation.");
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

        Vec3d look = mc.player.getRotationVector();
        double sp = power.get();
        mc.player.setVelocity(look.x * sp, look.y * sp, look.z * sp);
        info("Launched (power %.2f), dry.", sp);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
