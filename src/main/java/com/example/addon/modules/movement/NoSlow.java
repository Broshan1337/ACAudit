package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: NoSlow
 *
 * Cancels the vanilla movement slowdown that applies while using an item
 * (eating, drinking, blocking, drawing a bow). Vanilla multiplies your input
 * by 0.2 in that state; NoSlowMixin removes that multiplier while this module
 * is active, so you keep full walk/sprint speed mid-use.
 *
 * Subtlety control:
 *   partial-factor — instead of full speed (1.0), apply a fractional multiplier
 *                    (e.g. 0.5 = half the slowdown removed). Tests whether the
 *                    AC catches only full NoSlow or also partial speed exploits.
 *
 * DETECTION: re-derive the allowed speed server-side from the player's item-use
 * state. While the server believes the player isUsingItem, cap horizontal speed
 * to the 0.2x multiplier - a player moving at full speed while using an item is
 * an immediate flag. Do not trust client-reported velocity.
 */
public class NoSlow extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> partialFactor = sgGeneral.add(new DoubleSetting.Builder()
        .name("partial-factor")
        .description("Speed multiplier while using item (1.0 = full speed, 0.2 = vanilla slow). Tests partial NoSlow detection.")
        .defaultValue(1.0).range(0.2, 1.0).sliderRange(0.2, 1.0).build()
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

    public NoSlow() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-no-slow",
            "Removes the item-use movement slowdown. Tests server-side speed validation against item-use state.");
    }

    /** Called by NoSlowMixin — returns the configured speed factor instead of hardcoded 1.0. */
    public float getSpeedFactor() {
        return partialFactor.get().floatValue();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) { ticksActive++; }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
