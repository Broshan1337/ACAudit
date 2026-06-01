package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: AirJump
 *
 * Lets the player jump again while airborne. The server sees an upward velocity
 * spike with no ground contact beforehand - a textbook fly/air-movement trigger.
 * Ported from LiquidBounce AirJump (DoubleJump behavior).
 */
public class AirJump extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> maxJumps = sgGeneral.add(new IntSetting.Builder()
        .name("air-jumps").description("Extra jumps allowed before touching ground.")
        .defaultValue(1).range(1, 10).sliderRange(1, 5).build()
    );
    private final Setting<Double> jumpVelocity = sgGeneral.add(new DoubleSetting.Builder()
        .name("jump-velocity").description("Upward velocity applied per air jump.")
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

    private int used = 0;
    private boolean wasPressed = false;

    public AirJump() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-air-jump",
            "Jump again while airborne. Tests fly / air-movement detection.");
    }

    @Override
    public void onActivate() { used = 0; wasPressed = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;

        if (mc.player.isOnGround()) {
            used = 0;
            wasPressed = mc.options.jumpKey.isPressed();
            return;
        }

        boolean pressed = mc.options.jumpKey.isPressed();
        // rising edge of the jump key while airborne = one air jump
        if (pressed && !wasPressed && used < maxJumps.get()) {
            mc.player.setVelocity(mc.player.getVelocity().x, jumpVelocity.get(), mc.player.getVelocity().z);
            used++;
        }
        wasPressed = pressed;
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
