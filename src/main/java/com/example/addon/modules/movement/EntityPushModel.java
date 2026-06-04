package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Entity-Push Model (multi-entity push uncertainty probe)
 *
 * When entities collide with a player they push it, and the resulting displacement
 * depends on the number, positions and timing of every pusher — a genuinely hard
 * thing to simulate exactly, so an AC legitimately widens tolerance while the
 * player is in a crowd. The vector: stand in a real crowd (mobs, players) so the
 * push uncertainty is justified, then add a small horizontal delta hidden inside
 * the noise the AC already expects from being pushed.
 *
 * It only spends while a real crowd is present (it counts nearby entities); it does
 * not fabricate the push. MovementObserver grades whether the delta is absorbed.
 *
 *   What it exploits: per-entity push tolerance pooling into general slack while
 *     surrounded.
 *   Measurement AC: no crowd model; either ignores push or over-corrects it.
 *   Simulation AC: vulnerable if push uncertainty is summed loosely and not bounded
 *     by the actual maximum displacement the present entities could cause.
 *   Intent AC: bounds the tolerance to the maximum push the specific nearby
 *     entities could physically produce this tick.
 *   Fix (any well-implemented AC): bound push tolerance by the real entities
 *     present and their max push contribution; never grant open-ended slack for
 *     "being in a crowd".
 *
 * Run against your OWN server only.
 */
public class EntityPushModel extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> minEntities = sgGeneral.add(new IntSetting.Builder()
        .name("min-entities").description("Only spend the delta when at least this many entities are within push range.")
        .defaultValue(2).range(1, 20).sliderRange(1, 10).build()
    );
    private final Setting<Double> pushRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("push-radius").description("Horizontal distance (blocks) counted as touching/pushing.")
        .defaultValue(0.8).range(0.3, 3.0).sliderRange(0.3, 2.0).build()
    );
    private final Setting<Double> spendDelta = sgGeneral.add(new DoubleSetting.Builder()
        .name("spend-delta").description("Extra horizontal speed (b/t) added while in the crowd — the amount hidden in push noise.")
        .defaultValue(0.07).range(0.0, 0.5).sliderRange(0.0, 0.2).build()
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

    private int ticksActive = 0, spends = 0, maxCrowd = 0;

    public EntityPushModel() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "entity-push-model",
            "Spends a small illegal delta only while a real entity crowd is pushing you. Tests whether push uncertainty is bounded by the actual entities present.");
    }

    @Override
    public void onActivate() { ticksActive = 0; spends = 0; maxCrowd = 0; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;
        obs.tick();

        int crowd = 0;
        double r = pushRadius.get();
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || !e.isAlive()) continue;
            double dx = e.getX() - mc.player.getX();
            double dz = e.getZ() - mc.player.getZ();
            if (dx * dx + dz * dz <= r * r) crowd++;
        }
        maxCrowd = Math.max(maxCrowd, crowd);
        if (crowd < minEntities.get()) return;

        float fwd = mc.player.forwardSpeed, side = mc.player.sidewaysSpeed;
        double f, s;
        if (fwd == 0 && side == 0) { f = 1; s = 0; }
        else { double len = Math.sqrt(fwd * fwd + side * side); f = fwd / len; s = side / len; }
        double yaw = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        Vec3d v = mc.player.getVelocity();
        double dx = (f * -sin + s * cos) * spendDelta.get();
        double dz = (f *  cos + s * sin) * spendDelta.get();
        mc.player.setVelocity(v.x + dx, v.y, v.z + dz);
        spends++;
        obs.markSent();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d spends, max crowd %d.", ticksActive, spends, maxCrowd);
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
