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
 * AUDIT: Offset Boundary (prediction-tolerance mapper — instrument)
 *
 * A prediction-based anticheat accepts a client position if it lies within an
 * "offset" of where its simulation says the player could be. That offset is not a
 * single number — it legitimately differs by state (on ground, in air, in water,
 * on ice, sprinting). This module does not try to cheat; it MEASURES the boundary.
 * It raises a horizontal-speed delta in small steps via OffsetSeeker until the
 * server first corrects, then reports the exact tolerated value AND the state the
 * player was in when the boundary was found.
 *
 *   What it tells the operator: the real tolerated offset per state, to compare
 *     against what a correct vanilla simulation would permit there.
 *   Patch signal (what any well-implemented AC should do): the located boundary
 *     should equal — not exceed — the legal simulation envelope for that state. A
 *     boundary materially looser than vanilla physics is the slack an exploit
 *     lives in; tighten that state's tolerance to the simulation.
 *
 * Run against your OWN server only — keep moving in the state you want to map.
 */
public class OffsetBoundary extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final OffsetSeeker seeker = new OffsetSeeker(sgGeneral, "speed (b/t)", 0.28, 0.01, 20);
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
    private String boundaryState = null;

    public OffsetBoundary() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "offset-boundary",
            "Maps the exact horizontal-speed offset the AC tolerates in the player's current state. An instrument, not a bypass.");
    }

    @Override
    public void onActivate() { ticksActive = 0; boundaryState = null; seeker.onActivate(); obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        float fwd = mc.player.forwardSpeed, side = mc.player.sidewaysSpeed;
        if (fwd == 0 && side == 0) return; // only probe while the player is actually moving

        double len = Math.sqrt(fwd * fwd + side * side);
        double f = fwd / len, s = side / len;
        double yaw = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        double target = seeker.value();
        double dx = (f * -sin + s * cos) * target;
        double dz = (f *  cos + s * sin) * target;

        Vec3d v = mc.player.getVelocity();
        mc.player.setVelocity(dx, v.y, dz);
        obs.markSent();

        boolean wasFound = seeker.found();
        seeker.update(obs.setbackCount(), l -> info("%s", l));
        if (!wasFound && seeker.found()) boundaryState = currentState();
    }

    private String currentState() {
        if (mc.player == null) return "unknown";
        if (mc.player.isTouchingWater()) return "in-water";
        BlockPos below = mc.player.getBlockPos().down();
        var st = mc.world != null ? mc.world.getBlockState(below) : null;
        boolean onIce = st != null && (st.isOf(Blocks.ICE) || st.isOf(Blocks.PACKED_ICE) || st.isOf(Blocks.BLUE_ICE) || st.isOf(Blocks.FROSTED_ICE));
        if (onIce) return "on-ice";
        if (!mc.player.isOnGround()) return "in-air";
        if (mc.player.isSprinting()) return "sprinting-ground";
        return "walking-ground";
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active.", ticksActive);
            if (boundaryState != null) info("Boundary state: %s", boundaryState);
            seeker.report(l -> info("%s", l));
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
