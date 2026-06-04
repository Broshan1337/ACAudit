package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Input Launder (prediction input-ambiguity probe)
 *
 * A simulation AC validates a reported position by asking "is there ANY legal set
 * of inputs that produces this?" — it searches the space of possible inputs
 * (forward/back/strafe/sprint/jump) and accepts if one of them fits within
 * tolerance. The blind spot: the position can be explained by a legal input the
 * player is NOT actually performing. If the AC only checks that *some* legal input
 * fits, rather than that the fitting input matches the keys the client claims to be
 * pressing, a cheat can hide inside the union of legal predictions.
 *
 * This module reports a movement delta shaped like a different legal input than the
 * one really held — e.g. a sprint-speed forward delta while no sprint/forward input
 * is pressed, or a jump arc while grounded with no jump — and grades the outcome.
 *
 *   What it exploits: acceptance based on "an input exists" rather than "the input
 *     that fits is the one being pressed".
 *   Measurement AC: passes — the value is legal for some input.
 *   Simulation AC: passes if it tests only the input union, not input consistency.
 *   Intent AC: cross-checks the fitting input against the client's reported held
 *     keys / sprint flag and flags the contradiction.
 *   Fix (any well-implemented AC): when the only input that explains the motion
 *     contradicts the player's claimed input state, that is itself the violation.
 *
 * Run against your OWN server only.
 */
public class InputLaunder extends Module {
    public enum Mode { SPRINT_WHILE_IDLE, JUMP_WHILE_GROUNDED, STRAFE_SUBSTITUTE }
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which legal-input shape to report while the real input differs.")
        .defaultValue(Mode.SPRINT_WHILE_IDLE).build()
    );
    private final Setting<Integer> steps = sgGeneral.add(new IntSetting.Builder()
        .name("steps").description("Packets in the laundered sequence.")
        .defaultValue(8).range(2, 40).sliderRange(2, 20).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one sequence (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_8))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build()
    );

    private final PhysicsSequencer seq = new PhysicsSequencer(sgGeneral);
    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private boolean wasPressed = false;

    public InputLaunder() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "input-launder",
            "Reports a delta shaped like a legal input the player isn't pressing (sprint while idle, jump while grounded). Tests input-consistency vs input-union validation.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; wasPressed = false; obs.onActivate(); }

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
        seq.begin();

        for (int i = 0; i < steps.get(); i++) {
            switch (mode.get()) {
                // sprint-speed forward delta while the player reports no forward/sprint input
                case SPRINT_WHILE_IDLE -> seq.step(dirX * 0.2806, 0, dirZ * 0.2806, true);
                // a jump arc (legal under "jumped") while the player remains grounded with no jump press
                case JUMP_WHILE_GROUNDED -> {
                    double dy = i == 0 ? 0.42 : Math.max(-0.5, 0.42 - 0.08 * i);
                    seq.step(dirX * 0.2, dy, dirZ * 0.2, false);
                }
                // motion matching a strafe input while the player walks straight
                case STRAFE_SUBSTITUTE -> seq.step(dirZ * 0.21, 0, -dirX * 0.21, true);
            }
            packetsSent++;
        }
        obs.markSent();
        info("Laundered %d steps as %s.", steps.get(), mode.get());
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
