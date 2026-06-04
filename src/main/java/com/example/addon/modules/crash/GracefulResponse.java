package com.example.addon.modules.crash;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared graceful-degradation observer (review criterion 4).
 *
 * A crash/stress vector "passing" should mean the server handled it GRACEFULLY,
 * not merely that it happened not to crash. This composition helper grades the
 * server's reaction into one of:
 *
 *   REJECTED  — the server disconnected us; the kick reason text is captured
 *               verbatim (clean, intentional rejection is the ideal outcome).
 *   SILENT DROP — we fired but observed no resync/setback/slot-update at all:
 *               the server either ignored the packet or processed it without
 *               correcting state (the latter is the dangerous case).
 *   CORRECTED — the server sent slot resyncs / position setbacks: it noticed
 *               the bad input and reacted.
 *
 * It also samples ServerHealthMonitor (if active) for the min TPS seen during
 * the run, so the owner can see whether a vector degraded the tick loop and
 * whether it recovered. This is the measurement that turns "didn't crash" into
 * a meaningful, graded result.
 *
 * Composition, not inheritance: the owning module forwards lifecycle and packet
 * events (onActivate / tick / onReceive / onKick) and prints report() lines.
 * No @EventHandler here — helper objects are not on Meteor's event bus.
 */
public final class GracefulResponse {
    private final Setting<Boolean> observe;
    private final Setting<Integer> silenceWindow;

    private boolean kicked;
    private String kickReason;
    private int firedCount;
    private int responseCount;          // resync/setback packets seen since first fire
    private int ticksSinceFire = -1;

    private ServerHealthMonitor health; // lazily resolved
    private double minTps = 20.0;
    private boolean sawTps;

    public GracefulResponse(SettingGroup g) {
        observe = g.add(new BoolSetting.Builder()
            .name("observe-response")
            .description("Grade the server's reaction (kick reason, silent drop vs. resync, min TPS) on deactivate.")
            .defaultValue(true).build()
        );
        silenceWindow = g.add(new IntSetting.Builder()
            .name("silence-window-ticks")
            .description("Ticks to wait after a fire before a no-response counts as a silent drop.")
            .defaultValue(20).range(2, 200).sliderRange(2, 100)
            .visible(observe::get).build()
        );
    }

    public boolean enabled() { return observe.get(); }
    public boolean kicked()  { return kicked; }

    public void onActivate() {
        kicked = false; kickReason = null;
        firedCount = 0; responseCount = 0; ticksSinceFire = -1;
        health = null; minTps = 20.0; sawTps = false;
    }

    /** Record that the vector fired this tick. */
    public void markFired() {
        firedCount++;
        if (ticksSinceFire < 0) ticksSinceFire = 0;
    }

    /** Call once per module tick; samples TPS and advances the silence window. */
    public void tick() {
        if (!observe.get()) return;
        if (ticksSinceFire >= 0) ticksSinceFire++;
        if (health == null) health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && health.isActive() && health.isWarm()) {
            minTps = Math.min(minTps, health.getTps());
            sawTps = true;
        }
    }

    /** Forward every received packet here. */
    public void onReceive(Object packet) {
        if (!observe.get()) return;
        if (packet instanceof DisconnectS2CPacket d) {
            kicked = true;
            kickReason = d.reason().getString();
        } else if (packet instanceof ScreenHandlerSlotUpdateS2CPacket
                || packet instanceof InventoryS2CPacket
                || packet instanceof PlayerPositionLookS2CPacket) {
            responseCount++;
        }
    }

    /** Forward GameLeftEvent here (kick without a captured reason packet). */
    public void onKick() { kicked = true; }

    /** Emit grading lines. Caller should print each as a literal (e.g. info("%s", l)). */
    public void report(Consumer<String> printer) {
        if (!observe.get()) return;
        for (String l : reportLines()) printer.accept(l);
    }

    public List<String> reportLines() {
        List<String> out = new ArrayList<>();
        if (!observe.get()) return out;
        if (kicked) {
            out.add("  Response: REJECTED" + (kickReason != null && !kickReason.isBlank()
                ? " — \"" + kickReason + "\"" : " (disconnect, no reason text captured)"));
        } else if (firedCount > 0 && responseCount == 0) {
            out.add("  Response: SILENT DROP after " + ticksSinceFire + " ticks — no resync/setback seen"
                + " (server ignored it, or processed it without correcting — investigate the latter)");
        } else if (responseCount > 0) {
            out.add("  Response: server sent " + responseCount + " correction/resync packet(s) — it noticed and reacted");
        } else {
            out.add("  Response: nothing fired");
        }
        if (sawTps) {
            out.add(String.format("  TPS: min %.1f during run%s", minTps,
                minTps >= 19.0 ? " (held)" : " — degraded; check recovery with server-probe"));
        } else {
            out.add("  TPS: not sampled (enable server-health-monitor to grade tick-loop impact)");
        }
        return out;
    }

    public int silenceWindow() { return silenceWindow.get(); }
}
