package com.example.addon.modules.movement;

import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared movement-outcome observer (movement deep-coverage, the axis-2/axis-8 backbone).
 *
 * A movement vector "passing" should mean the anticheat genuinely MODELED the
 * physics/logic being exploited — verified by what the server actually did, not
 * assumed from what the cheat looked like on the client. This composition helper
 * grades the server's reaction to a probe:
 *
 *   SETBACK        — server rejected/corrected it (PlayerPositionLookS2CPacket).
 *                    A measurement OR physics AC that understood the vector sets
 *                    you back; the correction magnitude is recorded.
 *   SILENT_ACCEPT  — probe sent, no correction within the window: the dangerous
 *                    case, and the strongest client-side signal that the AC did
 *                    NOT model this sequence (each packet looked individually
 *                    legal so a measurement-only AC waved it through).
 *   KICK           — hard enforcement (DisconnectS2CPacket reason captured).
 *   DIVERGENCE     — the magnitude of each correction: small-but-repeated
 *                    corrections expose a tolerance the model never reconciles;
 *                    one large correction after a drift means the AC finally
 *                    snapped its model back to authoritative state.
 *   UNDER LOAD     — min TPS via crash.ServerHealthMonitor, to compare degraded
 *                    vs. baseline enforcement.
 *
 * Honest boundary: a client cannot read the AC's internal model, prediction
 * window, or violation level. Everything here is INFERRED from the server's
 * observable corrections/kicks — silent-accept + no-setback is the documented
 * proxy for "the model did not catch it", exactly as the Dupe tab infers a
 * bypassed Bukkit event from silent-accept + item delta.
 *
 * Composition, not inheritance: the owning module forwards onActivate / tick /
 * markSent / onReceive / onKick and prints report().
 */
public final class MovementObserver {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final Setting<Boolean> observe;
    private final Setting<Integer> window;
    private final Setting<Boolean> acPresent;

    private boolean kicked;
    private String kickReason;
    private int sent, setbacks, silent;
    private int ticksSinceSent = -1;
    private boolean awaitingEcho, sawSetbackThisWindow;

    private double maxCorrection, totalCorrection;
    private int corrections;
    private Vec3d lastAuthoritativePos;

    private ServerHealthMonitor health;
    private double minTps = 20.0;
    private boolean sawTps;

    public MovementObserver(SettingGroup g) {
        observe = g.add(new BoolSetting.Builder()
            .name("observe-outcome")
            .description("Grade what the server did with the probe: setback vs. silent-accept vs. kick, correction magnitude, min TPS.")
            .defaultValue(true).build()
        );
        window = g.add(new IntSetting.Builder()
            .name("outcome-window-ticks")
            .description("Ticks to wait after a probe for a server correction before classifying it as silently accepted.")
            .defaultValue(10).range(2, 200).sliderRange(2, 60)
            .visible(observe::get).build()
        );
        acPresent = g.add(new BoolSetting.Builder()
            .name("ac-present")
            .description("An anticheat IS installed on the target. When OFF, silent-accept is expected (nothing is judging the movement) and the report says so instead of flagging it — so you don't mistake 'no AC' for 'AC missed it'.")
            .defaultValue(true)
            .visible(observe::get).build()
        );
    }

    public boolean enabled() { return observe.get(); }
    public boolean kicked()  { return kicked; }
    public int setbackCount() { return setbacks; }
    public int silentCount() { return silent; }
    /** True only for the window after a probe before the server has either corrected or the window expired. */
    public boolean awaitingOutcome() { return awaitingEcho; }
    /** Largest single correction magnitude (blocks) seen so far, 0 if none. */
    public double maxCorrection() { return maxCorrection; }

    public void onActivate() {
        kicked = false; kickReason = null;
        sent = setbacks = silent = 0;
        ticksSinceSent = -1; awaitingEcho = false; sawSetbackThisWindow = false;
        maxCorrection = totalCorrection = 0; corrections = 0; lastAuthoritativePos = null;
        health = null; minTps = 20.0; sawTps = false;
    }

    /** Mark that a probe (one move / sequence) was just emitted. Opens the outcome window. */
    public void markSent() {
        sent++;
        ticksSinceSent = 0;
        awaitingEcho = true;
        sawSetbackThisWindow = false;
    }

    public void tick() {
        if (!observe.get()) return;
        if (ticksSinceSent >= 0) ticksSinceSent++;

        if (awaitingEcho && ticksSinceSent >= window.get()) {
            awaitingEcho = false;
            if (!sawSetbackThisWindow) silent++;
        }

        if (health == null) health = Modules.get().get(ServerHealthMonitor.class);
        if (health != null && health.isActive() && health.isWarm()) {
            minTps = Math.min(minTps, health.getTps());
            sawTps = true;
        }
    }

    public void onReceive(Object packet) {
        if (!observe.get()) return;
        if (packet instanceof DisconnectS2CPacket d) {
            kicked = true; kickReason = d.reason().getString();
        } else if (packet instanceof PlayerPositionLookS2CPacket p) {
            setbacks++;
            if (awaitingEcho) sawSetbackThisWindow = true;
            // Correction magnitude: how far the server moved us from where we reported.
            if (mc.player != null && p.relatives().isEmpty()) {
                Vec3d authoritative = p.change().position();
                Vec3d here = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                double d = here.distanceTo(authoritative);
                if (d > 0) {
                    corrections++;
                    totalCorrection += d;
                    maxCorrection = Math.max(maxCorrection, d);
                }
                lastAuthoritativePos = authoritative;
            }
        }
    }

    public void onKick() { kicked = true; }

    public Vec3d lastAuthoritativePos() { return lastAuthoritativePos; }

    public void report(Consumer<String> printer) {
        if (!observe.get()) return;
        for (String l : reportLines()) printer.accept(l);
    }

    public List<String> reportLines() {
        List<String> out = new ArrayList<>();
        if (!observe.get()) return out;
        if (kicked) {
            out.add("  Outcome: KICKED" + (kickReason != null && !kickReason.isBlank()
                ? " — \"" + kickReason + "\"" : ""));
        }
        out.add(String.format("  Echo: %d setback, %d silent-accept (of %d probes)", setbacks, silent, sent));
        if (silent > 0 && setbacks == 0) {
            if (acPresent.get())
                out.add("  → SILENT-ACCEPT on every probe: the AC did not model this sequence — INVESTIGATE");
            else
                out.add("  → SILENT-ACCEPT on every probe — but ac-present is OFF, so this is EXPECTED (nothing was judging the movement). Enable your AC and set ac-present to get a real result.");
        } else if (silent > 0) {
            out.add("  → mixed: some probes slipped through (" + silent + ")"
                + (acPresent.get() ? " — partial model coverage" : " — ac-present is OFF, interpret with that in mind"));
        } else if (setbacks > 0) {
            out.add("  → corrected every probe: the AC caught this vector");
        }
        if (corrections > 0)
            out.add(String.format("  Correction magnitude: max %.3f b, avg %.3f b over %d corrections",
                maxCorrection, totalCorrection / corrections, corrections));
        if (sawTps) out.add(String.format("  TPS: min %.1f%s", minTps, minTps >= 19.0 ? " (held)" : " — degraded"));
        return out;
    }
}
