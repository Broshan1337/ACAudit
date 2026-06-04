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
 * AUDIT: State-Machine Fuzz (transition-rate probe — axis 6)
 *
 * The server keeps a per-player movement state machine. This drives it through a
 * LEGAL sequence of transitions faster than any real player could: a tiny,
 * individually-plausible micro-jump (ground → air → ground, with matching Y bumps
 * and onGround flags) repeated far faster than the jump cooldown allows. Each
 * transition looks valid; the RATE of transitions is impossible.
 *
 *   What it exploits: that each state transition is validated in isolation, but
 *     the transition RATE (how fast the machine can legitimately cycle) is not.
 *   Measurement AC: passes — each ground/air flag matches a small Y change.
 *   Physics AC: flags if it enforces the jump cooldown / minimum air-time between
 *     ground contacts.
 *   Intent AC: flags a cycle no human input cadence can produce.
 *   Fix: rate-limit state transitions (jump cooldown, minimum air time) and
 *     validate the cadence of the whole sequence, not just each transition.
 *
 * Run against your OWN server only.
 */
public class StateMachineFuzz extends Module {
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> cycles = sgGeneral.add(new IntSetting.Builder()
        .name("cycles").description("Ground→air→ground micro-jump cycles to emit.")
        .defaultValue(8).range(1, 60).sliderRange(1, 30).build()
    );
    private final Setting<Double> hopHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("hop-height").description("Y bump per micro-jump (small = each transition individually plausible).")
        .defaultValue(0.1).range(0.01, 0.5).sliderRange(0.01, 0.3).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one fuzz sequence (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_7))
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

    public StateMachineFuzz() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "state-machine-fuzz",
            "Cycles ground/air transitions faster than the jump cooldown allows, each transition individually valid. Tests transition-rate limiting.");
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

        double h = hopHeight.get();
        seq.begin();
        for (int i = 0; i < cycles.get(); i++) {
            seq.step(0,  h, 0, false);   // leave ground (air)
            seq.step(0, -h, 0, true);    // return to ground (land) — one full cycle in 2 packets
            packetsSent += 2;
        }
        obs.markSent();
        info("Sent %d ground/air cycles.", cycles.get());
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
