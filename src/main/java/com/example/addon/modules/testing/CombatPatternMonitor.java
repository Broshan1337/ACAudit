package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;

/**
 * AUDIT: Combat Pattern Monitor
 *
 * Passively logs every outbound attack/swing packet and computes:
 *   - CPS (clicks per second) — the raw attack rate
 *   - Swing-to-attack ratio — vanilla always has one swing per attack
 *   - Tick distribution — how many attacks land in the same tick vs. spread
 *
 * This tells you what your AC should be flagging for combat modules. Run it with
 * no cheats active first to record the vanilla baseline, then again with your
 * combat/reach cheat to see what changed — the delta is what your AC must detect.
 *
 * Also useful for checking FastAttack: if attacks-per-tick > 1 without a swing
 * in between, that's already an AC flag.
 */
public class CombatPatternMonitor extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> reportInterval = sgGeneral.add(new IntSetting.Builder()
        .name("report-interval-s").description("Seconds between CPS/pattern reports.")
        .defaultValue(3).range(1, 30).sliderRange(1, 15).build()
    );
    private final Setting<Boolean> perTickLog = sgGeneral.add(new BoolSetting.Builder()
        .name("per-tick-log").description("Print how many attacks/swings went out each tick.")
        .defaultValue(false).build()
    );

    private int attacks, swings;
    private int attacksThisTick, swingsThisTick;
    private int multiAttackTicks;
    private long windowStart;

    public CombatPatternMonitor() {
        super(AddonTemplate.TESTING_CATEGORY, "combat-pattern-monitor",
            "Logs CPS, swing-to-attack ratio and per-tick attack distribution. Establishes combat baseline for AC calibration.");
    }

    @Override
    public void onActivate() {
        attacks = swings = attacksThisTick = swingsThisTick = multiAttackTicks = 0;
        windowStart = System.currentTimeMillis();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof PlayerInteractEntityC2SPacket) {
            attacks++; attacksThisTick++;
        } else if (event.packet instanceof HandSwingC2SPacket) {
            swings++; swingsThisTick++;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (perTickLog.get() && (attacksThisTick > 0 || swingsThisTick > 0)) {
            info("Tick: %d attacks  %d swings%s",
                attacksThisTick, swingsThisTick,
                attacksThisTick > 1 ? "  *** multi-attack ***" : "");
        }
        if (attacksThisTick > 1) multiAttackTicks++;
        attacksThisTick = 0; swingsThisTick = 0;

        long now = System.currentTimeMillis();
        long elapsed = now - windowStart;
        if (elapsed < reportInterval.get() * 1000L) return;

        double secs = elapsed / 1000.0;
        double cps = attacks / secs;
        double ratio = attacks == 0 ? 0 : (double) swings / attacks;
        info("Combat pattern (%.1fs): CPS=%.1f  swings/attack=%.2f  multi-attack-ticks=%d",
            secs, cps, ratio, multiAttackTicks);

        attacks = swings = multiAttackTicks = 0;
        windowStart = now;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }
}
