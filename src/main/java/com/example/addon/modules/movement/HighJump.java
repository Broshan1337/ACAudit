package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.JumpVelocityMultiplierEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: HighJump
 *
 * Multiplies the jump take-off velocity (vanilla ~0.42). A multiplier > 1 makes
 * the player leave the ground faster than physics allows. Classic vertical-speed
 * / "jump height" check trigger. Ported from LiquidBounce HighJump (Vanilla mode).
 */
public class HighJump extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> multiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("multiplier").description("Jump velocity multiplier (1.0 = vanilla).")
        .defaultValue(2.0).range(1.0, 10.0).sliderRange(1.0, 5.0).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public HighJump() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-high-jump",
            "Multiplies jump take-off velocity. Tests vertical-speed / jump-height detection.");
    }

    @EventHandler
    private void onJumpMultiplier(JumpVelocityMultiplierEvent event) {
        event.multiplier = multiplier.get().floatValue();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
