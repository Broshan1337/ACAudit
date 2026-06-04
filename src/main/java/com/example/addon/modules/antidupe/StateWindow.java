package com.example.addon.modules.antidupe;

import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;

/**
 * Shared state-transition window (review axis 2: exploit states the plugin
 * never considered). Detects a brief server-side state transition and opens a
 * firing window so the module can replay inventory actions INSIDE it, where item
 * ownership is momentarily ambiguous.
 *
 * Client-detectable triggers (verified packets):
 *   TELEPORT     — PlayerPositionLookS2CPacket (inventory ambiguous src↔dst)
 *   DIMENSION    — PlayerRespawnS2CPacket (cross-world handoff)
 *   CHUNK_UNLOAD — UnloadChunkS2CPacket (container's chunk leaving memory)
 *   LAG_SPIKE    — ServerHealthMonitor TPS below a threshold (async event reorder)
 *
 * NOT client-detectable, so exposed as KEYBIND (operator creates the window and
 * presses the key): world-save (/save-all), plugin reload (/reload), async DB
 * flush. The module documents that it cannot sense these — the operator triggers
 * them. Honest about the boundary rather than faking a detector.
 */
public final class StateWindow {
    public enum Trigger { TELEPORT, DIMENSION, CHUNK_UNLOAD, LAG_SPIKE, KEYBIND }

    private final Setting<Trigger> trigger;
    private final Setting<Integer> windowTicks;
    private final Setting<Double> lagTps;

    private int remaining = 0;
    private ServerHealthMonitor health;

    public StateWindow(SettingGroup g) {
        trigger = g.add(new EnumSetting.Builder<Trigger>()
            .name("state-trigger")
            .description("Transition to fire inside. KEYBIND = operator-created windows (world-save / reload / DB flush) that the client cannot sense.")
            .defaultValue(Trigger.TELEPORT).build()
        );
        windowTicks = g.add(new IntSetting.Builder()
            .name("state-window-ticks")
            .description("How many ticks the firing window stays open after the transition.")
            .defaultValue(6).range(1, 60).sliderRange(1, 30).build()
        );
        lagTps = g.add(new DoubleSetting.Builder()
            .name("lag-tps-threshold")
            .description("Open the window when estimated TPS drops below this (LAG_SPIKE).")
            .defaultValue(15.0).range(1.0, 20.0).sliderRange(5.0, 20.0)
            .visible(() -> trigger.get() == Trigger.LAG_SPIKE).build()
        );
    }

    public Trigger trigger() { return trigger.get(); }

    public void onActivate() { remaining = 0; health = null; }

    /** Forward received packets; opens the window on a matching transition. */
    public void onReceive(Object packet) {
        switch (trigger.get()) {
            case TELEPORT     -> { if (packet instanceof PlayerPositionLookS2CPacket) open(); }
            case DIMENSION    -> { if (packet instanceof PlayerRespawnS2CPacket) open(); }
            case CHUNK_UNLOAD -> { if (packet instanceof UnloadChunkS2CPacket) open(); }
            default -> {}
        }
    }

    /** KEYBIND trigger: operator opens the window manually. */
    public void forceOpen() { open(); }

    private void open() { remaining = windowTicks.get(); }

    /** Call once per tick. Returns whether we are currently inside the window. */
    public boolean inWindow() {
        if (trigger.get() == Trigger.LAG_SPIKE) {
            if (health == null) health = Modules.get().get(ServerHealthMonitor.class);
            if (health != null && health.isActive() && health.isWarm() && health.getTps() < lagTps.get()) open();
        }
        if (remaining > 0) { remaining--; return true; }
        return false;
    }
}
