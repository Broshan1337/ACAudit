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
 * AUDIT: Ground-State Forge (ground-history consistency probe — axis 1)
 *
 * The onGround bit is client-controlled, and many checks trust it. This reports
 * ground/air transitions that are impossible given the reported Y-trajectory:
 *
 *   FAKE_LANDING       — report onGround=true on a tick where Y is still falling
 *                        fast. A real landing requires the descent to actually
 *                        reach a surface and decelerate to zero vertical speed.
 *   IMPOSSIBLE_APPROACH— arrive at "landed" after a flat, high-speed horizontal
 *                        approach with no descent/deceleration curve at all.
 *   GROUND_FLICKER     — alternate onGround true/false every packet with no Y
 *                        change, faster than any real land/leave cycle.
 *
 *   What it exploits: that a landing must be CONSISTENT with the approach
 *     trajectory — ground contact is derivable from position + world geometry.
 *   Measurement AC: trusts the onGround bit; passes.
 *   Physics AC: recomputes ground contact from position vs. the block below and
 *     ignores the client bit; flags the mismatch.
 *   Intent AC: also checks the landing follows a real descent/deceleration curve,
 *     not an instant flat-to-grounded transition.
 *   Fix: never trust client onGround. Derive it server-side from the AABB vs.
 *     world, and validate that any reported landing is consistent with the prior
 *     descent trajectory.
 *
 * Run against your OWN server only.
 */
public class GroundStateForge extends Module {
    public enum Mode { FAKE_LANDING, IMPOSSIBLE_APPROACH, GROUND_FLICKER }
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which ground-history inconsistency to report.")
        .defaultValue(Mode.FAKE_LANDING).build()
    );
    private final Setting<Integer> steps = sgGeneral.add(new IntSetting.Builder()
        .name("steps").description("Packets in the sequence.")
        .defaultValue(8).range(2, 40).sliderRange(2, 20).build()
    );
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("Per-step distance for descent/approach (b/step).")
        .defaultValue(0.3).range(0.0, 0.8).sliderRange(0.0, 0.6).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one sequence (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_2))
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

    public GroundStateForge() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ground-state-forge",
            "Reports onGround transitions inconsistent with the Y-trajectory (fake landing, no-approach, flicker). Tests whether the AC derives ground state instead of trusting the client bit.");
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
        double sp = speed.get();
        seq.begin();

        switch (mode.get()) {
            case FAKE_LANDING -> {
                // Keep falling fast, but report onGround=true the whole way down.
                for (int i = 0; i < steps.get(); i++) { seq.step(0, -sp, 0, true); packetsSent++; }
            }
            case IMPOSSIBLE_APPROACH -> {
                // Flat high-speed horizontal run, last packet claims onGround with no descent.
                for (int i = 0; i < steps.get() - 1; i++) { seq.step(dirX * sp, 0, dirZ * sp, false); packetsSent++; }
                seq.step(dirX * sp, 0, dirZ * sp, true); packetsSent++;
            }
            case GROUND_FLICKER -> {
                // No Y change; flip the ground bit every packet faster than any real cycle.
                for (int i = 0; i < steps.get(); i++) { seq.groundFlag(i % 2 == 0); packetsSent++; }
            }
        }
        obs.markSent();
        info("Sent ground-state-forge sequence (%s, %d steps).", mode.get(), steps.get());
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
