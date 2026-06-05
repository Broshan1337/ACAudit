package com.example.addon.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Structural conflict classes for simultaneous multi-vector runs.
 *
 * Two vectors that both drive {@code mc.player.setVelocity} every tick overwrite
 * each other — only one wins per tick. Two vectors that both cancel/hold outbound
 * packets via {@code PacketEvent.Send} interfere on the send pipeline. Such pairs
 * cannot truly co-run, so the combo runner and the parallel sweep both consult
 * this to warn and suggest a substitute.
 *
 * Shared so ComboOrchestrator and AutoAuditRunner classify identically.
 */
public final class ConflictClasses {
    private ConflictClasses() {}

    /** Vectors that drive mc.player.setVelocity every tick — only ONE can win. */
    public static final Set<String> VELOCITY = Set.of(
        "speed", "bhop", "ac-high-jump", "ac-air-jump", "low-hop-fly", "omni-sprint", "stealth-fly",
        "reset-vl", "riptide-launch", "momentum-break", "uncertainty-farm", "offset-boundary",
        "compensation-boundary", "source-attribution", "baseline-poison", "entity-push-model",
        "setback-interference", "accumulator-soak", "fp-cover", "respawn-model-reset",
        "vehicle-move", "vehicle-sim-gap");

    /** Vectors that cancel/hold/rewrite outbound packets via PacketEvent.Send. */
    public static final Set<String> PACKET_HOLD = Set.of(
        "ac-blink", "ping-spoof", "transaction-timing", "model-drift", "packet-order-skew");

    public record Result(boolean conflict, List<String> warnings) {}

    /**
     * Classify a set of co-running vector names. Returns whether a structural
     * conflict exists and the human warning + suggestion lines.
     */
    public static Result check(List<String> names) {
        List<String> vel = new ArrayList<>(), hold = new ArrayList<>(), warn = new ArrayList<>();
        for (String n : names) {
            if (VELOCITY.contains(n)) vel.add(n);
            if (PACKET_HOLD.contains(n)) hold.add(n);
        }
        boolean conflict = false;
        if (vel.size() > 1) {
            conflict = true;
            warn.add("CONFLICT: velocity-writers " + vel + " overwrite each other — only one wins per tick.");
            warn.add("  Suggest: keep ONE velocity vector; add a packet-layer/dupe/crash vector (e.g. ping-spoof, shift-click-race, packet-spammer).");
        }
        if (hold.size() > 1) {
            conflict = true;
            warn.add("CONFLICT: packet-holders " + hold + " interfere on the send pipeline.");
            warn.add("  Suggest: keep ONE packet-holder; add a velocity or dupe vector instead.");
        }
        return new Result(conflict, warn);
    }
}
