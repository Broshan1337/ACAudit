package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: Combo-Test (simultaneous dual-vector stress)
 *
 * Activates TWO named modules at the same time and holds both for a configurable
 * window, then reports the combined TPS impact. The goal is to find combinations
 * that bypass rate-limits or cause disproportionate lag — e.g., movement-crash
 * + packet-spammer simultaneously can saturate different server queues in a way
 * neither does alone.
 *
 * Run against your OWN local server only.
 */
public class ComboTest extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<String> moduleA = sgGeneral.add(new StringSetting.Builder()
        .name("module-a").description("First module to activate.")
        .defaultValue("movement-crash").build()
    );
    private final Setting<String> moduleB = sgGeneral.add(new StringSetting.Builder()
        .name("module-b").description("Second module to activate simultaneously.")
        .defaultValue("packet-spammer").build()
    );
    private final Setting<Integer> durationSecs = sgGeneral.add(new IntSetting.Builder()
        .name("duration-seconds").description("How long to hold both modules active.")
        .defaultValue(10).range(1, 120).sliderRange(2, 60).build()
    );
    private final Setting<Double> tpsFloor = sgGeneral.add(new DoubleSetting.Builder()
        .name("tps-floor").description("FAIL threshold.")
        .defaultValue(19.0).range(0.0, 20.0).sliderRange(10.0, 20.0).build()
    );

    private Module a, b;
    private ServerHealthMonitor health;
    private double minTps;
    private long startMs;

    public ComboTest() {
        super(AddonTemplate.TESTING_CATEGORY, "combo-test",
            "Activates two named modules simultaneously and reports combined TPS impact. Tests compound-vector server response.");
    }

    @Override
    public void onActivate() {
        a = Modules.get().get(moduleA.get());
        b = Modules.get().get(moduleB.get());
        if (a == null) { error("Module '%s' not found.", moduleA.get()); toggle(); return; }
        if (b == null) { error("Module '%s' not found.", moduleB.get()); toggle(); return; }

        health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && !health.isActive()) health.toggle();

        if (!a.isActive()) a.toggle();
        if (!b.isActive()) b.toggle();
        minTps = 20.0; startMs = System.currentTimeMillis();
        info("Combo: '%s' + '%s' for %ds.", moduleA.get(), moduleB.get(), durationSecs.get());
    }

    @Override
    public void onDeactivate() {
        if (a != null && a.isActive()) a.toggle();
        if (b != null && b.isActive()) b.toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (a == null || health == null) return;
        if (health.isWarm()) minTps = Math.min(minTps, health.getTps());

        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed >= durationSecs.get() * 1000L) {
            boolean pass = minTps >= tpsFloor.get();
            info("Combo result — '%s' + '%s': min TPS %.1f → %s",
                moduleA.get(), moduleB.get(), minTps, pass ? "PASS" : "FAIL");
            toggle();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        warning("Disconnected during combo '%s' + '%s' — FAIL", moduleA.get(), moduleB.get());
        if (isActive()) toggle();
    }
}
