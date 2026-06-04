package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Source Attribution (velocity-source distinction probe)
 *
 * A high horizontal speed is legitimate from ice, an elytra, or a boat, and illegal
 * from nothing. A correct AC must ATTRIBUTE the speed to its source: the same
 * velocity should pass with a real source present and flag without it. This module
 * holds a configurable speed and reports the player's actual source state each run,
 * so the operator runs it once WITH a real source (on ice / gliding / in a vehicle)
 * and once WITHOUT, and compares the correction counts.
 *
 * If the outcomes match (both pass, or both flag) regardless of source, the AC is
 * either too lenient (accepting sourceless speed) or too strict (false-flagging the
 * legitimate source) — the report makes that comparison explicit.
 *
 *   Patch signal (any well-implemented AC): identical speed must be ACCEPTED with a
 *     real velocity source present and CORRECTED without one. Attribution must come
 *     from authoritative world/entity state, never from the speed itself.
 *
 * Run on YOUR server, once with a source and once without, and compare.
 */
public class SourceAttribution extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("Horizontal speed (b/t) to hold while moving — pick a value above normal walk so a sourceless run should flag.")
        .defaultValue(0.45).range(0.21, 3.0).sliderRange(0.28, 1.0).build()
    );

    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0;
    private String observedSource = "none";

    public SourceAttribution() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "source-attribution",
            "Holds a fixed speed and reports the real velocity-source state. Run with and without a source to test attribution.");
    }

    @Override
    public void onActivate() { ticksActive = 0; observedSource = "none"; obs.onActivate(); }

    private String source() {
        if (mc.player == null) return "none";
        if (mc.player.isGliding()) return "elytra";
        if (mc.player.getVehicle() != null) return "vehicle";
        if (mc.player.isTouchingWater()) return "water";
        BlockPos below = mc.player.getBlockPos().down();
        var st = mc.world != null ? mc.world.getBlockState(below) : null;
        if (st != null && (st.isOf(Blocks.ICE) || st.isOf(Blocks.PACKED_ICE) || st.isOf(Blocks.BLUE_ICE) || st.isOf(Blocks.FROSTED_ICE)))
            return "ice";
        return "none";
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        float fwd = mc.player.forwardSpeed, side = mc.player.sidewaysSpeed;
        if (fwd == 0 && side == 0) return;
        observedSource = source();

        double len = Math.sqrt(fwd * fwd + side * side);
        double f = fwd / len, s = side / len;
        double yaw = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        Vec3d v = mc.player.getVelocity();
        mc.player.setVelocity((f * -sin + s * cos) * speed.get(), v.y, (f * cos + s * sin) * speed.get());
        obs.markSent();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks at %.3f b/t, last source observed: %s.", ticksActive, speed.get(), observedSource);
            info("→ compare this run's setbacks against a run in the opposite source state; matching outcomes mean attribution is missing.");
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
