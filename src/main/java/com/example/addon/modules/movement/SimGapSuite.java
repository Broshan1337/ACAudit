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
 * AUDIT: Simulation-Gap Suite (block-interaction prediction-gap probe)
 *
 * The hardest thing for a server-side physics simulation to reproduce exactly is
 * the special-case physics of certain blocks. Each has bespoke rules the
 * simulation must model precisely or it produces a WRONG prediction — and a wrong
 * prediction is tolerance the AC grants for the wrong reason. This suite reports
 * the characteristic motion of each such block while the player is NOT in/on it,
 * testing whether the AC gates that physics behind the block actually being there.
 *
 *   POWDER_SNOW   — slow controlled sink with horizontal freedom (powder-snow fall).
 *   BUBBLE_UP     — steady lift (soul-sand bubble column) with no column present.
 *   BUBBLE_DOWN   — steady downward pull (magma bubble column) while "swimming".
 *   COBWEB_HOVER  — near-zero fall (cobweb drag cancels gravity) with no cobweb.
 *   HONEY_SLIDE   — slow wall-slide descent (honey block) with no honey wall.
 *   EDGE_LANDING  — report onGround=true while standing on the exact corner/edge of
 *                   a block, where edge-vs-centre collision resolution is ambiguous.
 *   SCAFFOLD_STACK— chain sub-step-height rises reporting grounded, as if bridging.
 *
 *   What it exploits: special-case block physics being applied from the MOTION
 *     rather than from confirmed block presence.
 *   Measurement AC: passes — each value is legal for the block whose physics it
 *     borrows.
 *   Simulation AC: flags only if its simulation checks the actual block at the
 *     player's position before allowing that block's physics.
 *   Intent AC: flags motion that requires a block the world does not contain there.
 *   Fix (any well-implemented AC): gate every special-case physics rule behind the
 *     authoritative block state at the relevant position; never infer the block
 *     from the motion it would produce, and model edge/centre collision exactly.
 *
 * Run against your OWN server only.
 */
public class SimGapSuite extends Module {
    public enum Mode { POWDER_SNOW, BUBBLE_UP, BUBBLE_DOWN, COBWEB_HOVER, HONEY_SLIDE, EDGE_LANDING, SCAFFOLD_STACK }
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which block's special-case physics to borrow without the block present.")
        .defaultValue(Mode.COBWEB_HOVER).build()
    );
    private final Setting<Integer> steps = sgGeneral.add(new IntSetting.Builder()
        .name("steps").description("Packets in the sequence.")
        .defaultValue(12).range(2, 60).sliderRange(2, 30).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one sequence (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_9))
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

    public SimGapSuite() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "sim-gap-suite",
            "Reports special-case block physics (powder snow / bubble column / cobweb / honey / edge-landing / scaffold) without the block present. Tests block-gated physics validation.");
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
        double baseX = mc.player.getX(), baseZ = mc.player.getZ();
        seq.begin();

        for (int i = 0; i < steps.get(); i++) {
            switch (mode.get()) {
                case POWDER_SNOW   -> seq.step(dirX * 0.1, -0.05, dirZ * 0.1, false); // slow sink, horizontal freedom
                case BUBBLE_UP     -> seq.step(0, 0.07, 0, false);                    // soul-sand column lift, no column
                case BUBBLE_DOWN   -> seq.step(dirX * 0.05, -0.09, dirZ * 0.05, false); // magma column pull while "swimming"
                case COBWEB_HOVER  -> seq.step(dirX * 0.05, -0.003, dirZ * 0.05, false); // cobweb cancels fall, no cobweb
                case HONEY_SLIDE   -> seq.step(0, -0.05, 0, false);                   // honey wall-slide, no honey wall
                case EDGE_LANDING  -> {
                    // Stand on the exact block edge: snap X/Z to a block boundary, claim grounded.
                    double ex = Math.floor(baseX) + (i % 2 == 0 ? 0.0001 : 0.9999);
                    double ez = Math.floor(baseZ) + (i % 2 == 0 ? 0.9999 : 0.0001);
                    seq.stepTo(ex, mc.player.getY(), ez, true);
                }
                case SCAFFOLD_STACK-> seq.step(dirX * 0.15, 0.49, dirZ * 0.15, true); // sub-step rises, bridging-grounded
            }
            packetsSent++;
        }
        obs.markSent();
        info("Sent sim-gap (%s, %d steps).", mode.get(), steps.get());
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
