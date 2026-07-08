package com.example.addon.audit;

/**
 * Honest labelling of whether a real threat actor could actually trigger a vector on a
 * default Paper server. The central question of the crash-category review: a module that
 * needs op, creative, or special server config is not testing a real threat — it is
 * testing a misconfigured server, and the report must say so.
 */
public enum RealismTag {
    /** Any non-op player can trigger this on a default Paper server, no special items/config. */
    REALISTIC("any non-op player, default Paper, no special items or config"),
    /** Needs an obtainable item / world object / triggered state (all reachable by a normal player). */
    CONDITIONAL("needs an obtainable item, world object, or triggered state (reachable by any player)"),
    /** Requires creative mode — a real threat on creative-plot / build / mini-game servers. */
    CREATIVE("requires creative mode — a real threat on creative-plot / build servers"),
    /** Requires operator permissions — tests a misconfigured server, not a normal-player threat. */
    OPERATOR("requires operator permissions — not a normal-player threat"),
    /** The vector is real but the current implementation cannot actually trigger it from a client on current Paper (kept as a regression check). */
    THEORETICAL("vector is real but not client-triggerable on current Paper — kept as a regression check");

    public final String explanation;
    RealismTag(String explanation) { this.explanation = explanation; }

    public String tag() { return "[" + name() + "]"; }
}
