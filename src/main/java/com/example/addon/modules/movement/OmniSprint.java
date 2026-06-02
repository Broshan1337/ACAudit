package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Omni-Sprint (sprint flag in all movement directions)
 *
 * Forces the sprint flag true on every tick regardless of movement direction.
 * Vanilla only permits sprinting while moving forward (input.forwardSpeed > 0)
 * and not under a slowing effect. Sprinting backward or sideways is physically
 * impossible in vanilla, so the server can cross-check it.
 *
 * Subtlety controls:
 *   directional-only — only force sprint when moving sideways or backward
 *                      (s != 0 || f < 0). Tests whether the AC specifically
 *                      looks for sprint-while-forward or any illegal sprint angle.
 *   burst-ticks      — sprint for N ticks then rest for rest-ticks. Tests
 *                      whether the AC flags sustained omni-sprint or also catches
 *                      intermittent sprint bursts.
 *
 * Combination: Bhop+OmniSprint+Speed (full BHS combo).
 *
 * DETECTION: cross-check the sprint flag against the player's movement input
 * vector. If sprint=true but the player is moving sideways or backward
 * (forwardSpeed ≤ 0), flag as omni-sprint.
 */
public class OmniSprint extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> directionalOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("directional-only")
        .description("Only force sprint when moving sideways or backward. Tests angular sprint validation.")
        .defaultValue(false).build()
    );
    private final Setting<Integer> burstTicks = sgGeneral.add(new IntSetting.Builder()
        .name("burst-ticks")
        .description("Sprint for this many ticks per cycle (0 = always on). Tests intermittent sprint detection.")
        .defaultValue(0).range(0, 100).sliderRange(0, 40).build()
    );
    private final Setting<Integer> restTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rest-ticks")
        .description("Ticks of no forced sprint per cycle. Visible when burst-ticks > 0.")
        .defaultValue(0).range(0, 100).sliderRange(0, 40)
        .visible(() -> burstTicks.get() > 0).build()
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
    private int burstCounter = 0;

    public OmniSprint() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "omni-sprint",
            "Forces sprint in all directions. Tests sprint vs movement-vector cross-check.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; burstCounter = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        burstCounter++;

        int bt = burstTicks.get(), rt = restTicks.get();
        boolean inBurst = (bt == 0 && rt == 0) || (bt + rt > 0 && (burstCounter % (bt + rt)) < bt);
        if (!inBurst) { mc.player.setSprinting(false); return; }

        if (directionalOnly.get()) {
            float f = mc.player.forwardSpeed, s = mc.player.sidewaysSpeed;
            if (s == 0 && f >= 0) return;
        }

        mc.player.setSprinting(true);
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
        if (mc.player != null) mc.player.setSprinting(false);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
