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
 * AUDIT: Timer
 *
 * Scales the client game-tick clock. A multiplier > 1 makes the client process
 * more game ticks per real second, so movement/actions are sent faster than
 * wall-clock allows - effectively speed and faster everything, while each
 * individual packet still looks per-tick legal.
 *
 * The actual tick scaling is applied in TimerMixin (RenderTickCounter.Dynamic);
 * this module just exposes the multiplier and on/off state to it.
 *
 * DETECTION: rate-limit by WALL-CLOCK, not tick count. A client cannot legibly
 * send more than ~20 movement packets per real second; count moves over a
 * 1-second sliding window and flag any sustained excess regardless of how
 * "legal" each individual packet is.
 */
public class Timer extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> multiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("multiplier")
        .description("Tick-speed multiplier (1.0 = vanilla, >1 = faster).")
        .defaultValue(2.0).range(0.1, 10.0).sliderRange(0.1, 5.0).build()
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

    public Timer() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-timer",
            "Speeds up the client tick clock. Tests wall-clock rate limiting vs. per-tick checks.");
    }

    /** Read by TimerMixin. Returns 1.0 when inactive so the mixin is a no-op. */
    public float getMultiplier() {
        return isActive() ? multiplier.get().floatValue() : 1.0f;
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) { ticksActive++; }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
