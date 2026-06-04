package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;

/**
 * AUDIT: Low-TPS Reach (lag-leniency reach probe)
 *
 * When a server's TPS drops, entity positions update less often and lag
 * compensation widens, so an AC may legitimately grant a little extra reach
 * tolerance. The audit question: does that leniency scale sanely, or does server
 * lag open a reach window an attacker can exploit precisely when the server is
 * already struggling? This pairs the Crash tab (which can induce load) with a reach
 * test: it periodically attacks the nearest entity that is BEYOND normal reach and
 * records the distance against the current TPS, so the operator can see whether
 * over-reach correlates with low TPS.
 *
 * Not a kill-aura: one swing + attack per interval, only on a target past normal
 * reach.
 *
 *   Patch signal (any well-implemented AC): reach leniency under low TPS must be
 *     bounded and derived from the actual compensated position uncertainty, not an
 *     open margin that grows with lag. The maximum accepted reach should stay close
 *     to vanilla regardless of TPS.
 *
 * Honest limit: correlate the printed (distance, TPS) attempts with your server's
 * reach-check logs. Run on YOUR server; optionally enable a Crash-tab load module.
 */
public class LowTPSReach extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> normalReach = sgGeneral.add(new DoubleSetting.Builder()
        .name("normal-reach").description("Reach (blocks) considered normal — only attack targets beyond this.")
        .defaultValue(3.0).range(2.5, 4.0).sliderRange(2.8, 3.5).build()
    );
    private final Setting<Double> maxReach = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-reach").description("Farthest target distance (blocks) to attempt.")
        .defaultValue(6.0).range(3.0, 10.0).sliderRange(3.5, 8.0).build()
    );
    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval").description("Ticks between over-reach attempts.")
        .defaultValue(10).range(2, 100).sliderRange(2, 40).build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int tickTimer, attempts, lowTpsAttempts;
    private double maxDistAttempted;

    public LowTPSReach() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "low-tps-reach",
            "Attacks the nearest target beyond normal reach and records distance vs TPS. Tests whether reach leniency scales with server lag.");
    }

    @Override
    public void onActivate() { tickTimer = 0; attempts = 0; lowTpsAttempts = 0; maxDistAttempted = 0; }

    private double tps() {
        ServerHealthMonitor shm = Modules.get().get(ServerHealthMonitor.class);
        if (shm != null && shm.isActive() && shm.isWarm()) return shm.getTps();
        return 20.0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (++tickTimer < interval.get()) return;
        tickTimer = 0;

        Entity best = null; double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || !(e instanceof LivingEntity) || !e.isAlive()) continue;
            double d = mc.player.distanceTo(e);
            if (d > normalReach.get() && d <= maxReach.get() && d < bestDist) { best = e; bestDist = d; }
        }
        if (best == null) return;

        mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(best, mc.player.isSneaking()));
        attempts++;
        maxDistAttempted = Math.max(maxDistAttempted, bestDist);
        double t = tps();
        if (t < 18.0) lowTpsAttempts++;
        info("Over-reach attempt: %.2f b at %.1f TPS%s.", bestDist, t, t < 18.0 ? " (LOW)" : "");
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d over-reach attempts (%d under low TPS), farthest %.2f b.", attempts, lowTpsAttempts, maxDistAttempted);
            info("→ correlate accepted hits with TPS in your server logs: accepted over-reach concentrated at low TPS is the gap.");
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
