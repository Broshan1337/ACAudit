package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
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
 * Omni-sprint matters in two contexts:
 *   1. Speed: sprint adds a ~30% horizontal speed bonus; sideways/backward
 *      sprint at sprint speed exceeds the vanilla walking cap for those
 *      directions, which a directional speed check would catch.
 *   2. Hit detection: some reach and knockback calculations use sprinting
 *      state; a cheater who claims sprint bonuses in all directions may
 *      get more knockback on hits than physics allows.
 *
 * DETECTION: cross-check the sprint flag against the player's movement input
 * vector. If sprint=true but the player is moving sideways or backward
 * (forwardSpeed ≤ 0), flag as omni-sprint; strip the sprint bonus from any
 * speed or reach calculation for that tick.
 */
public class OmniSprint extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public OmniSprint() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "omni-sprint",
            "Forces sprint in all directions. Tests sprint vs movement-vector cross-check.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
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
