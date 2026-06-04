package com.example.addon.modules.crash;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * Shared pre-stress hook (review criterion 3).
 *
 * A server that handles a vector cleanly at 20 TPS may fail at 15 TPS, when its
 * validation checks are competing with a saturated tick loop. This composition
 * helper optionally spins up a background load module BEFORE the main vector
 * fires, so the vector is exercised against an already-degraded server. Combined
 * with GracefulResponse's TPS sampling, the owner can compare a vector's
 * baseline result against its degraded-state result — exactly the "do my
 * defenses hold under load" question.
 *
 * It also enables ServerHealthMonitor so the degradation is actually measured.
 */
public final class PreStress {
    private final Setting<Boolean> enabled;
    private final Setting<String> loadModule;

    private Module load;

    public PreStress(SettingGroup g) {
        enabled = g.add(new BoolSetting.Builder()
            .name("pre-stress")
            .description("Enable a background load module first, so the main vector is tested while the server is already degraded.")
            .defaultValue(false).build()
        );
        loadModule = g.add(new StringSetting.Builder()
            .name("pre-stress-module")
            .description("Background load module to run (e.g. packet-spammer, arm-animation-flood).")
            .defaultValue("packet-spammer")
            .visible(enabled::get).build()
        );
    }

    public boolean enabled() { return enabled.get(); }
    public String moduleName() { return loadModule.get(); }

    /** Enable the background load + health monitor. Pass the owning module so it never targets itself. */
    public void onActivate(Module self) {
        if (!enabled.get()) return;
        ServerHealthMonitor shm = Modules.get().get(ServerHealthMonitor.class);
        if (shm != null && !shm.isActive()) shm.toggle();

        load = Modules.get().get(loadModule.get());
        if (load == null || load == self) { load = null; return; }
        if (!load.isActive()) load.toggle();
    }

    /** Disable the background load we started. */
    public void onDeactivate() {
        if (load != null && load.isActive()) load.toggle();
        load = null;
    }
}
