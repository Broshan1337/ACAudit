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
 * AUDIT: Momentum Break (acceleration-continuity probe — axis 1)
 *
 * Emits a position SEQUENCE where every per-step distance is individually legal
 * (≤ a normal sprint-jump move) but the acceleration BETWEEN steps is impossible:
 * the reported speed jumps from low (~0.10 b/step) to high (~0.40 b/step) in a
 * single step with no jump, knockback, or external force to explain it. A player
 * in a real physics sim cannot change velocity that fast on the ground.
 *
 *   What it exploits: that acceleration (Δvelocity) is bounded, not just velocity.
 *   Measurement AC: passes — every individual per-packet distance is under the
 *     speed cap, so a value-checker sees nothing wrong.
 *   Physics AC: flags — |v_t − v_{t-1}| exceeds the max acceleration for the
 *     surface/state; the model knows you can't accelerate that hard.
 *   Intent AC: flags earlier — there is no input/jump/hit event that would
 *     produce this acceleration, so it could not come from a real player.
 *   Fix: validate the change in velocity against a per-state acceleration cap,
 *     not only the instantaneous speed. Track v_{t-1} per player.
 *
 * Honest boundary: this emits a SEQUENCE of move packets and the server validates
 * the deltas between consecutive reported positions — exactly what we vary. The
 * MovementObserver grades whether the server set us back (it modeled acceleration)
 * or silently accepted the sequence (it only measured per-packet speed).
 *
 * Run against your OWN server only.
 */
public class MomentumBreak extends Module {
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> lowSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("low-speed").description("Per-step distance in the slow phase (b/step).")
        .defaultValue(0.10).range(0.0, 0.4).sliderRange(0.0, 0.3).build()
    );
    private final Setting<Double> highSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("high-speed").description("Per-step distance in the fast phase (b/step). Each step still legal; the JUMP between phases is not.")
        .defaultValue(0.40).range(0.0, 0.9).sliderRange(0.1, 0.6).build()
    );
    private final Setting<Integer> steps = sgGeneral.add(new IntSetting.Builder()
        .name("steps").description("Total packets in the sequence (first half slow, second half fast).")
        .defaultValue(8).range(2, 40).sliderRange(2, 20).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one sequence (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_1))
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

    public MomentumBreak() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "momentum-break",
            "Reports a move sequence with legal per-step speed but impossible acceleration between steps. Tests whether the AC models acceleration, not just speed.");
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
        int half = Math.max(1, steps.get() / 2);

        seq.begin();
        for (int i = 0; i < steps.get(); i++) {
            double sp = i < half ? lowSpeed.get() : highSpeed.get();
            seq.step(dirX * sp, 0, dirZ * sp, true);
            packetsSent++;
        }
        obs.markSent();
        info("Sent momentum-break sequence (%d steps, %.2f→%.2f b/step).", steps.get(), lowSpeed.get(), highSpeed.get());
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
    private void onSendSuppress(PacketEvent.Send event) { if (seq.filterSend(event.packet)) event.cancel(); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
