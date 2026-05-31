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

/**
 * AUDIT: Bhop (NoJumpDelay + auto-jump)
 *
 * Re-jumps the instant the player lands instead of obeying the vanilla jump
 * cooldown, and force-sprints. Produces a continuous low-hop with no ground
 * settle ticks - tests jump-delay enforcement and on-ground/air state tracking.
 * Equivalent observable behavior to LiquidBounce NoJumpDelay + Sprint.
 */
public class Bhop extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> jumpVelocity = sgGeneral.add(new DoubleSetting.Builder()
        .name("jump-velocity").description("Upward velocity on each hop (vanilla 0.42).")
        .defaultValue(0.42).range(0.1, 1.0).sliderRange(0.1, 0.8).build()
    );
    private final Setting<Boolean> autoSprint = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-sprint").description("Force sprint so each hop carries sprint speed.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public Bhop() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "bhop",
            "Auto-jumps with no landing delay. Tests jump-delay enforcement and ground-state tracking.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (autoSprint.get()) mc.player.setSprinting(true);

        // re-jump on every landing while jump is held
        if (mc.player.isOnGround() && mc.options.jumpKey.isPressed()) {
            mc.player.setVelocity(mc.player.getVelocity().x, jumpVelocity.get(), mc.player.getVelocity().z);
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
