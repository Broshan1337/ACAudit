package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;

/**
 * AUDIT: Fast-Attack (hit-rate / attack-cooldown test)
 *
 * Sends many attacks per tick at the entity you're looking at, far above the
 * vanilla attack cooldown (1.9+ adds a per-weapon cooldown; vanilla also caps at
 * one meaningful hit per tick). Tests whether your server enforces hit rate
 * server-side rather than trusting the client to respect the cooldown.
 *
 * "10000 hits/sec" isn't physically reachable - the client runs 20 ticks/sec and
 * a packet-rate limiter caps how many attack packets you can send per tick - so
 * the real test is: does the server reject attacks that arrive faster than the
 * cooldown, and does it cap damage application per tick? An unbounded hit rate is
 * the basis of "one-tap" / multi-hit combat cheats.
 *
 * Patch signal: enforce the attacker's weapon cooldown server-side (track
 * lastAttackTick per player); apply at most one valid melee hit per cooldown
 * window; ignore - don't queue - early attacks.
 */
public class FastAttack extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> hitsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("hits-per-tick")
        .description("Attacks per tick on the looked-at entity. Start low to test cooldown enforcement.")
        .defaultValue(1).range(1, 200).sliderRange(1, 50).build()
    );
    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing").description("Swing the hand each hit.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public FastAttack() {
        super(AddonTemplate.CRASH_CATEGORY, "fast-attack",
            "Attacks the looked-at entity many times per tick. Tests server-side attack-cooldown / hit-rate enforcement.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        if (mc.player == null || mc.interactionManager == null) return;
        if (!(mc.crosshairTarget instanceof EntityHitResult ehr)) return;
        Entity target = ehr.getEntity();
        if (target == null) return;

        for (int i = 0; i < hitsPerTick.get(); i++) {
            mc.interactionManager.attackEntity(mc.player, target);
            if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
