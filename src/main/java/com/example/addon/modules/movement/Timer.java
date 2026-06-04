package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Timer
 *
 * Scales the client game-tick clock. A multiplier > 1 makes the client process
 * more game ticks per real second, so movement/actions are sent faster than
 * wall-clock allows — effectively speed and faster-everything, while each
 * individual packet still looks per-tick legal.
 *
 * Subtlety control:
 *   burst-mode — alternate between burst-ticks at full multiplier and rest-ticks
 *                at normal speed (1.0×). Tests whether the AC catches intermittent
 *                timer abuse or only sustained clock inflation. A real laggy client
 *                bursts ticks similarly; this probes whether the AC can distinguish
 *                the two.
 *
 * DETECTION: rate-limit by WALL-CLOCK, not tick count. A client cannot legibly
 * send more than ~20 movement packets per real second; count moves over a
 * 1-second sliding window and flag any sustained excess.
 */
public class Timer extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> multiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("multiplier").description("Tick-speed multiplier (1.0 = vanilla, >1 = faster).")
        .defaultValue(2.0).range(0.1, 10.0).sliderRange(0.1, 5.0).build()
    );
    private final Setting<Boolean> burstMode = sgGeneral.add(new BoolSetting.Builder()
        .name("burst-mode")
        .description("Alternate between full multiplier and 1.0×. Tests intermittent timer detection.")
        .defaultValue(false).build()
    );
    private final Setting<Integer> burstTicks = sgGeneral.add(new IntSetting.Builder()
        .name("burst-ticks").description("Ticks at full multiplier per cycle.")
        .defaultValue(20).range(1, 200).sliderRange(1, 80)
        .visible(burstMode::get).build()
    );
    private final Setting<Integer> restTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rest-ticks").description("Ticks at 1.0× per cycle.")
        .defaultValue(40).range(1, 200).sliderRange(1, 80)
        .visible(burstMode::get).build()
    );
    private final Setting<Boolean> driftMode = sgGeneral.add(new BoolSetting.Builder()
        .name("clock-drift")
        .description("Ignore multiplier and run a tiny sustained clock drift (sub-1%) instead. Undetectable per tick but accumulates to a large position error over a minute — tests long-window wall-clock validation.")
        .defaultValue(false).build()
    );
    private final Setting<Double> driftPercent = sgGeneral.add(new DoubleSetting.Builder()
        .name("drift-percent").description("How much faster than real time, in percent (0.1 = 0.1% ≈ undetectable per tick).")
        .defaultValue(0.1).range(0.01, 5.0).sliderRange(0.01, 1.0)
        .visible(driftMode::get).build()
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

    public Timer() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-timer",
            "Speeds up the client tick clock. Tests wall-clock rate limiting vs. per-tick checks.");
    }

    /** Read by TimerMixin. Returns 1.0 when inactive so the mixin is a no-op. */
    public float getMultiplier() {
        if (!isActive()) return 1.0f;
        if (driftMode.get()) return (float) (1.0 + driftPercent.get() / 100.0);
        if (burstMode.get()) {
            int bt = burstTicks.get(), rt = restTicks.get();
            int cycle = bt + rt;
            boolean inBurst = cycle > 0 && (ticksActive % cycle) < bt;
            return inBurst ? multiplier.get().floatValue() : 1.0f;
        }
        return multiplier.get().floatValue();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) { ticksActive++; obs.tick(); }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
