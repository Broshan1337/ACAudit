package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: False-Positive Cover (known-FP exploitation probe)
 *
 * Every anticheat deliberately tolerates some scenarios because enforcing them
 * strictly would false-flag legitimate players — riptide in rain, elytra clipping a
 * wall, being shoved by a piston, sliding off an ice edge, vehicle position
 * desync, movement near the void, knockback during lag. These exemptions are
 * documented in issue trackers and community channels. The audit question is NOT
 * "can we find a new bypass" — it is whether the AC has CLOSED THE GAP between its
 * known false positive and someone deliberately exploiting that exact scenario as
 * cover.
 *
 * The operator performs the real triggering condition (actually glide into a wall,
 * actually ride a boat on ice, etc.) and this module adds a MINIMAL illegal delta on
 * top, asking: does the tolerance that exists for the legitimate FP also swallow the
 * cheat? MovementObserver grades whether the delta is absorbed.
 *
 *   What it exploits: an FP exemption scoped loosely enough to cover deliberate
 *     abuse of the same scenario.
 *   Patch signal (any well-implemented AC): an FP exemption must be scoped tightly
 *     enough that the legitimate scenario passes but the scenario-plus-illegal-delta
 *     still flags. Exemptions should widen tolerance by exactly the amount the real
 *     edge case needs — never an open margin a cheat can hide inside.
 *
 * The 'scenario' setting is a label/reminder of which real condition to perform for
 * fidelity. Run on YOUR server, in the matching real scenario.
 */
public class FPCover extends Module {
    public enum Scenario { ELYTRA_WALL, ICE_EDGE, VEHICLE_DESYNC, NEAR_VOID, KNOCKBACK_LAG, PISTON_PUSH, RIPTIDE_RAIN }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Scenario> scenario = sgGeneral.add(new EnumSetting.Builder<Scenario>()
        .name("scenario").description("Documented FP scenario to abuse as cover — PERFORM the matching real condition while this runs.")
        .defaultValue(Scenario.ICE_EDGE).build()
    );
    private final Setting<Double> coverDelta = sgGeneral.add(new DoubleSetting.Builder()
        .name("cover-delta").description("Minimal extra horizontal speed (b/t) hidden inside the FP tolerance — keep it small.")
        .defaultValue(0.06).range(0.0, 0.4).sliderRange(0.0, 0.2).build()
    );
    private final Setting<Boolean> onlyWhenPlausible = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-plausible").description("Only add the delta when the player state loosely matches the scenario (airborne / in-vehicle / low-Y), so the cover is credible.")
        .defaultValue(true).build()
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

    private int ticksActive = 0, spends = 0;

    public FPCover() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "fp-cover",
            "Adds a minimal illegal delta while you perform a documented false-positive scenario. Tests whether FP tolerance also passes a cheat.");
    }

    @Override
    public void onActivate() { ticksActive = 0; spends = 0; obs.onActivate(); }

    private boolean plausible() {
        if (mc.player == null) return false;
        return switch (scenario.get()) {
            case ELYTRA_WALL, RIPTIDE_RAIN -> mc.player.isGliding() || !mc.player.isOnGround();
            case VEHICLE_DESYNC -> mc.player.getVehicle() != null;
            case NEAR_VOID -> mc.player.getY() < 5;
            case KNOCKBACK_LAG, PISTON_PUSH, ICE_EDGE -> true;
        };
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        if (onlyWhenPlausible.get() && !plausible()) return;

        float fwd = mc.player.forwardSpeed, side = mc.player.sidewaysSpeed;
        double f, s;
        if (fwd == 0 && side == 0) { f = 1; s = 0; }
        else { double len = Math.sqrt(fwd * fwd + side * side); f = fwd / len; s = side / len; }
        double yaw = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        Vec3d v = mc.player.getVelocity();
        double dx = (f * -sin + s * cos) * coverDelta.get();
        double dz = (f *  cos + s * sin) * coverDelta.get();
        mc.player.setVelocity(v.x + dx, v.y, v.z + dz);
        spends++;
        obs.markSent();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %s, %d ticks, %d delta spends.", scenario.get(), ticksActive, spends);
            info("→ if these spends were silently accepted, the %s false-positive tolerance also covers a cheat. INVESTIGATE its scope.", scenario.get());
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
