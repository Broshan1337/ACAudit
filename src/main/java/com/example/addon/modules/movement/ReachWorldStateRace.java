package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.Hand;

/**
 * AUDIT: Reach World-State Race (combat ↔ entity-update timing probe)
 *
 * This is the one genuinely narrow concurrency surface worth probing: an AC's
 * reach check reads the TARGET entity's position, and that position is being
 * updated by a separate code path (the entity's own movement / velocity / teleport
 * sync) than the one that handles the attack. If the reach check reads the target's
 * LIVE position instead of the lag-compensated position the attacker actually saw,
 * an attack timed to land exactly as the target's position update is being applied
 * can be measured against a transient/just-moved position.
 *
 * This module watches inbound position/velocity updates for the crosshair target
 * and fires exactly one attack on the tick such an update lands, maximising the
 * chance the reach check and the position update interleave. It is NOT a kill-aura;
 * it sends one swing + one attack per race.
 *
 *   What it exploits: reach measured against live target position rather than the
 *     compensated position history.
 *   Measurement AC: measures distance to wherever the target currently is; racy.
 *   Simulation AC: safe only if it reconstructs the target's position at the
 *     attacker's acknowledged tick from a transaction-anchored history.
 *   Intent AC: rejects an attack whose validity depends on which side of a
 *     position update it was processed.
 *   Fix (any well-implemented AC): compute reach against the target position from
 *     the compensated history at the attacker's acknowledged tick, never the live
 *     position, so the result is independent of update interleaving.
 *
 * Honest limit: the client cannot read the server's reach verdict — correlate the
 * race count printed here with your server's reach-check logs. Run on YOUR server.
 */
public class ReachWorldStateRace extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ticks").description("Minimum ticks between race attacks (avoids spamming the target).")
        .defaultValue(10).range(1, 100).sliderRange(2, 40).build()
    );
    private final Setting<Boolean> velocityUpdates = sgGeneral.add(new BoolSetting.Builder()
        .name("race-velocity-updates").description("Fire on the target's velocity updates (knockback) too, not only position syncs.")
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

    private int ticksActive = 0, races = 0, cooldown = 0;
    private boolean fireQueued = false;

    public ReachWorldStateRace() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "reach-world-state-race",
            "Fires one attack on the exact tick the crosshair target's position/velocity update lands. Tests whether reach uses live vs compensated target position.");
    }

    @Override
    public void onActivate() { ticksActive = 0; races = 0; cooldown = 0; fireQueued = false; obs.onActivate(); }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.onReceive(event.packet);
        if (mc.player == null || mc.targetedEntity == null) return;
        int targetId = mc.targetedEntity.getId();
        boolean match = false;
        if (event.packet instanceof EntityPositionSyncS2CPacket p && p.id() == targetId) match = true;
        else if (velocityUpdates.get() && event.packet instanceof EntityVelocityUpdateS2CPacket v && v.getEntityId() == targetId) match = true;
        if (match) fireQueued = true; // process on the next tick, on the game thread
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        if (cooldown > 0) cooldown--;

        if (fireQueued && cooldown == 0 && mc.targetedEntity != null) {
            mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(mc.targetedEntity, mc.player.isSneaking()));
            races++;
            cooldown = cooldownTicks.get();
            obs.markSent();
        }
        fireQueued = false;
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d race attacks fired.", ticksActive, races);
            info("→ correlate the %d races with your server's reach-check logs: any accepted hit during a target update is the gap.", races);
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
