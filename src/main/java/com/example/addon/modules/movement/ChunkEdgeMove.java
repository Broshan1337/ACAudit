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
 * AUDIT: Chunk-Edge Move (seam-validation probe — axis 3)
 *
 * Moving across a chunk boundary forces the server to validate against two chunks
 * at once. This reports a sequence that repeatedly crosses and re-crosses the
 * nearest chunk seam with borderline-large steps, probing whether there is a
 * brief validation gap exactly at the edge (a common place for collision/ground
 * checks to fall back to "unloaded → allow").
 *
 *   What it exploits: that boundary handoffs are a classic place for a validator
 *     to lapse (one chunk's data not yet consulted, the other already swapped).
 *   Measurement AC: passes — per-step distance is borderline but legal.
 *   Physics AC: must consult BOTH chunks at the seam; flags if the path crosses
 *     geometry it failed to load.
 *   Intent AC: notices the movement is pinned to the seam, which no real path is.
 *   Fix: validate edge-crossing moves against both chunks; never treat a
 *     momentarily-unconsulted chunk as empty/allow.
 *
 * Stand near a chunk boundary, press the key. Run against your OWN server only.
 */
public class ChunkEdgeMove extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> crossings = sgGeneral.add(new IntSetting.Builder()
        .name("crossings").description("How many times to cross and re-cross the seam.")
        .defaultValue(6).range(1, 40).sliderRange(1, 20).build()
    );
    private final Setting<Double> stepSize = sgGeneral.add(new DoubleSetting.Builder()
        .name("step-size").description("Distance of each seam-crossing step (b). Borderline-large probes the gap.")
        .defaultValue(0.45).range(0.1, 2.0).sliderRange(0.1, 1.0).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires the seam-oscillation sequence.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_5)).build()
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

    public ChunkEdgeMove() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "chunk-edge-move",
            "Oscillates across the nearest chunk seam with borderline steps. Tests for a validation gap at chunk boundaries.");
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

        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        // Pick the axis whose nearest chunk boundary we're closest to, and the sign toward it.
        double x = mc.player.getX(), z = mc.player.getZ();
        double distX = Math.abs(((x % 16) + 16) % 16 - 8);   // distance from an x-seam center metric
        double distZ = Math.abs(((z % 16) + 16) % 16 - 8);
        boolean alongX = distX >= distZ;                     // cross along whichever axis is nearer a seam
        double step = stepSize.get();

        seq.begin();
        double sign = 1;
        for (int i = 0; i < crossings.get(); i++) {
            if (alongX) seq.step(sign * step, 0, 0, true);
            else        seq.step(0, 0, sign * step, true);
            packetsSent++;
            sign = -sign;                                    // re-cross
        }
        obs.markSent();
        info("Sent %d seam crossings along %s.", crossings.get(), alongX ? "X" : "Z");
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
