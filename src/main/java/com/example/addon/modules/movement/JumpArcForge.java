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
 * AUDIT: Jump-Arc Forge (parabolic-arc shape probe — axis 1)
 *
 * A real jump follows y = v₀t − ½gt² : a parabola with a fixed relationship
 * between peak height, time-to-peak, and the symmetry of ascent vs. descent.
 * This reports a "jump" whose individual Y values are all reachable but whose
 * ARC SHAPE could never be produced by gravity:
 *
 *   WRONG_TIMING      — correct peak height, but reached in the wrong number of
 *                       ticks (time-axis scaled): right altitude, impossible rate.
 *   ASYMMETRIC        — more horizontal distance covered during ascent than during
 *                       descent (or vice-versa); real gravity is symmetric.
 *   FLAT_TOP          — hover at the peak for several packets; real apexes are a
 *                       single instantaneous turning point.
 *
 *   What it exploits: that the WHOLE arc must fit one gravity model, not just
 *     that each Y is individually attainable.
 *   Measurement AC: passes — peak height and per-tick Y deltas are all in range.
 *   Physics AC: fits the points to v₀/g and flags the shape/timing error.
 *   Intent AC: also flags the unnaturally constant horizontal speed through the
 *     vertical move (no air-strafe micro-variance a human produces).
 *   Fix: validate the jump as a trajectory against the gravity model (peak,
 *     time-to-peak, ascent/descent symmetry), not peak height + per-tick deltas.
 *
 * Run against your OWN server only.
 */
public class JumpArcForge extends Module {
    public enum Mode { WRONG_TIMING, ASYMMETRIC, FLAT_TOP }
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which arc-shape error to report.")
        .defaultValue(Mode.WRONG_TIMING).build()
    );
    private final Setting<Double> peak = sgGeneral.add(new DoubleSetting.Builder()
        .name("peak-height").description("Target peak height in blocks (vanilla jump ~1.25).")
        .defaultValue(1.25).range(0.5, 4.0).sliderRange(0.5, 2.5).build()
    );
    private final Setting<Integer> arcTicks = sgGeneral.add(new IntSetting.Builder()
        .name("arc-ticks").description("Packets spanning the full up-and-down arc.")
        .defaultValue(12).range(4, 60).sliderRange(4, 30).build()
    );
    private final Setting<Double> horizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed").description("Forward distance per packet during the arc (b/step).")
        .defaultValue(0.25).range(0.0, 0.6).sliderRange(0.0, 0.5).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one arc (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_3))
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

    public JumpArcForge() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "jump-arc-forge",
            "Reports a jump with correct peak height but physically wrong arc shape/timing/symmetry. Tests whether the AC validates the whole trajectory against gravity.");
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
        int n = arcTicks.get();
        double h = peak.get();
        double baseY = mc.player.getY();
        double prevY = baseY;

        seq.begin();
        for (int i = 1; i <= n; i++) {
            double frac = (double) i / n;            // 0..1 across the arc
            // Symmetric parabola peaking at the middle: y = 4h * frac * (1-frac).
            double targetY = baseY + 4 * h * frac * (1 - frac);
            double hStep = horizontalSpeed.get();

            switch (mode.get()) {
                case WRONG_TIMING -> { /* same peak height, but n is set wrong by the operator → wrong rate */ }
                case ASYMMETRIC -> {
                    // Cover most horizontal distance on the way up, almost none coming down.
                    hStep = frac < 0.5 ? horizontalSpeed.get() * 1.8 : horizontalSpeed.get() * 0.2;
                }
                case FLAT_TOP -> {
                    // Clamp the top third to the peak so it hovers instead of turning over.
                    if (frac > 0.33 && frac < 0.66) targetY = baseY + h;
                }
            }
            double dy = targetY - prevY;
            prevY = targetY;
            seq.step(dirX * hStep, dy, dirZ * hStep, false);
            packetsSent++;
        }
        obs.markSent();
        info("Sent jump-arc-forge (%s, peak %.2f over %d packets).", mode.get(), h, n);
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
