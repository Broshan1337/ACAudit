package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Physics Anomaly (effect/state physics-model probe — axis 7)
 *
 * One module, seven trajectories that are each valid under SOME physics model but
 * impossible under the player's ACTUAL state — testing whether the AC checks that
 * the player is genuinely in the state whose physics they're using:
 *
 *   GRAVITY      — descend at exactly terminal velocity from the first packet,
 *                  with no acceleration phase (we never sped up into that speed).
 *   STEP_STACK   — chain half-block step-ups (each ≤ step height) to gain height
 *                  faster than a jump while every step looks like a legal step.
 *   SWIM         — move at swim vectors (gentle upward glide) without being in water.
 *   SLOW_FALL    — descend at the slow-falling rate without the effect.
 *   LEVITATION   — ascend at the levitation rate without the effect.
 *   VEHICLE      — carry boat/minecart horizontal speed as an on-foot player.
 *   SCAFFOLD     — move horizontally in the air reporting onGround=true every
 *                  packet (as if placing blocks under yourself) with no blocks.
 *
 *   What it exploits: that physics rules are state-dependent; the AC must verify
 *     the player actually HAS the effect/vehicle/blocks before allowing its physics.
 *   Measurement AC: passes — each value is legal under the borrowed model.
 *   Physics AC: flags if it applies the player's REAL state's model.
 *   Intent AC: flags trajectories valid under one model but impossible under the
 *     state the player is actually in.
 *   Fix: gate each physics model behind authoritative state (effect present?
 *     in water? in a vehicle? block under foot?) — never infer the state from the
 *     movement that the state would permit.
 *
 * Run against your OWN server only.
 */
public class PhysicsAnomaly extends Module {
    public enum Mode { GRAVITY, STEP_STACK, SWIM, SLOW_FALL, LEVITATION, VEHICLE, SCAFFOLD }
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which borrowed-physics trajectory to report.")
        .defaultValue(Mode.GRAVITY).build()
    );
    private final Setting<Integer> steps = sgGeneral.add(new IntSetting.Builder()
        .name("steps").description("Packets in the sequence.")
        .defaultValue(10).range(2, 60).sliderRange(2, 30).build()
    );
    private final Setting<Double> rate = sgGeneral.add(new DoubleSetting.Builder()
        .name("rate").description("Per-packet magnitude (blocks): terminal-V / step-height / climb-rate, by mode.")
        .defaultValue(0.4).range(0.0, 1.0).sliderRange(0.0, 0.6).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one sequence (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_6))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build()
    );

    private final PhysicsSequencer seq = new PhysicsSequencer(sgGeneral);
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
    private boolean wasPressed = false;

    public PhysicsAnomaly() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "physics-anomaly",
            "Reports trajectories valid under a borrowed physics model (gravity/step/swim/slow-fall/levitation/vehicle/scaffold) the player isn't actually in. Tests state-gated physics validation.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; wasPressed = false;
        obs.onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        boolean fire;
        if (trigger.get() == Trigger.EACH_TICK) fire = true;
        else { boolean p = key.get().isPressed(); fire = p && !wasPressed; wasPressed = p; }
        if (!fire) return;

        double yaw = Math.toRadians(mc.player.getYaw());
        double dirX = -Math.sin(yaw), dirZ = Math.cos(yaw);
        double r = rate.get();
        seq.begin();

        for (int i = 0; i < steps.get(); i++) {
            switch (mode.get()) {
                case GRAVITY    -> seq.step(0, -r, 0, false);                 // constant terminal V, no ramp
                case STEP_STACK -> seq.step(dirX * 0.2, 0.5, dirZ * 0.2, true); // +half-block per packet, "grounded"
                case SWIM       -> seq.step(dirX * r, 0.04, dirZ * r, false);  // swim glide, not in water
                case SLOW_FALL  -> seq.step(dirX * 0.1, -0.05, dirZ * 0.1, false); // slow-fall rate, no effect
                case LEVITATION -> seq.step(0, 0.05 * Math.max(1, r * 10), 0, false); // climb, no effect
                case VEHICLE    -> seq.step(dirX * r, 0, dirZ * r, true);      // boat-speed on foot
                case SCAFFOLD   -> seq.step(dirX * 0.21, 0, dirZ * 0.21, true);// airborne but onGround=true
            }
            packetsSent++;
        }
        obs.markSent();
        info("Sent physics-anomaly (%s, %d steps).", mode.get(), steps.get());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
