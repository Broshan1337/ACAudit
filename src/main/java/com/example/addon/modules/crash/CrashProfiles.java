package com.example.addon.modules.crash;

import com.example.addon.audit.RealismTag;
import com.example.addon.audit.Severity;

import java.util.HashMap;
import java.util.Map;

import static com.example.addon.audit.RealismTag.*;
import static com.example.addon.audit.Severity.*;

/**
 * Central registry of {@link CrashProfile}s, keyed by module name.
 *
 * <p>Keeping every crash vector's realism labelling in ONE file is deliberate: the whole
 * point of the review was to make the category honest about whether a real threat actor
 * could trigger each vector, and a single table is far easier to audit for that than
 * 44 scattered constants. {@link GracefulResponse} looks a module's profile up here on
 * activate (by {@code module.name}) and prints it, so the user always sees the realism
 * tag, severity, a safe first-run intensity, and what to watch in logs.
 *
 * <p>Adding a new crash module? Add its profile here (CONTRIBUTING enforces it).
 */
public final class CrashProfiles {
    private CrashProfiles() {}

    private static final Map<String, CrashProfile> MAP = new HashMap<>();

    private static void p(String name, RealismTag realism, Severity sev, String vuln, String safe, String watch) {
        MAP.put(name, CrashProfile.of(realism, sev, vuln, safe, watch));
    }

    static {
        // --- REALISTIC: any non-op player, default Paper ---
        p("packet-spammer", REALISTIC, HIGH, "raw inbound packet-rate limiting at the Netty stage", "100/tick STRESS, or PROBE to find the limit", "'Sent too many packets' kick = the limiter working; a TPS drop without a kick = no throttle");
        p("arm-animation-flood", REALISTIC, HIGH, "O(viewers) swing-animation rebroadcast saturation", "50/tick, PROBE", "outbound packet rate climbing; other players' render lag");
        p("movement-crash", REALISTIC, HIGH, "move rate limiting (FLOOD) + inter-tick continuity (SUBTLE)", "SUBTLE 2.9 b/t, or FLOOD PROBE", "position setbacks; a SUBTLE walk that is NOT setback = missing continuity check");
        p("position-crash", REALISTIC, CRITICAL, "movement bounds + finiteness checking (large/huge/infinite/NaN/finite-nasty — absorbs extreme-velocity + nan-position)", "LARGE, 100 packets, on-tick OFF", "chunk-map misses; a poisoned NaN position that breaks every later distance calc until restart");
        p("stats-request-flood", REALISTIC, MEDIUM, "per-player rate-limit on REQUEST_STATS (cheap-send/expensive-serialize)", "40/tick", "CPU on stat serialization; no per-player throttle");
        p("respawn-race", REALISTIC, HIGH, "respawn-state gating + transition atomicity", "20/tick", "repeated player re-init; position/inventory reload while alive");
        p("channel-flood", REALISTIC, HIGH, "per-type metadata rate limiting + custom-payload decode bounds", "ALL 200/tick, or CUSTOM_PAYLOAD", "plugin-channel handler CPU; oversized payload allocations");
        p("chunk-ack-spoof", REALISTIC, HIGH, "chunk-send flow-control clamp (dishonest throughput credit)", "10000 credit, 20 acks/tick", "unbounded chunk serialization on the main thread");
        p("block-interaction-spam", REALISTIC, MEDIUM, "plugin event-hook overhead + block-use rate limiting", "20/tick", "PlayerInteractEvent listener CPU; a block action applied N times/tick");
        p("nbt-query-flood", REALISTIC, HIGH, "NBT-query rate limiting + permission-before-serialize ordering", "50/tick", "large block-entity/entity NBT serialized per query; no per-player budget");
        p("entity-spam", REALISTIC, MEDIUM, "entity-interaction rate limiting + attack cooldown + removal-tick race", "100/tick", "combat pipeline CPU; interactions honoured against a removed entity");
        p("recipe-advancement-fuzz", REALISTIC, MEDIUM, "recipe-id validation + advancement-tab handling", "4/tick", "unknown recipe-id lookups; garbage advancement-tab allocations");
        p("unloaded-chunk-interact", REALISTIC, HIGH, "chunk-loaded-for-player validation on far interactions", "4/tick, 2000 blocks", "forced synchronous chunk loads from client coordinates (Paper async chunk edge)");
        p("interact-crash", REALISTIC, MEDIUM, "reach + world-bounds validation on interaction coordinates", "15/tick, NoCom", "world lookups at OOB/Infinity coords; unbounded reach");
        p("beacon-crash", REALISTIC, MEDIUM, "beacon-update gating (no beacon open) + rate limiting", "500/tick", "beacon effect processing with no open beacon menu");
        p("packet-order-chaos", REALISTIC, MEDIUM, "handler precondition validation against out-of-order packets", "10/tick", "clicks on unknown syncIds; STOP without START; stale-state handling");
        p("worldgen-crawler", REALISTIC, HIGH, "no synchronous chunk generation from client movement + per-player new-chunk rate limit (Paper async-chunk abuse)", "SPIRAL, step 0.9, 8/tick, PROBE mode", "main-thread chunk-generation spikes; TPS drifting down as fresh chunks load; no packet-limiter kick");
        p("econ-gui-overload", REALISTIC, HIGH, "async off-thread economy transactions + bounded DB connection pool + PlaceholderAPI caching", "'sell all', 5 cmds + 10 clicks/tick, PROBE", "DB connection-pool exhaustion; scheduler backlog; TPS dropping during shop transactions");
        p("scheduler-soak", REALISTIC, HIGH, "bounded async task queues + cache expiry + deterministic pool return (slow-degradation defence)", "4 cmds/sec, 120s window", "TPS drifting DOWN over minutes with no recovery; growing scheduled-task / cache counts");
        p("completion-crash", REALISTIC, HIGH, "tab-complete parser depth cap + plugin onTabComplete hardening", "NESTED_JSON one-shot, or PLUGIN_FUZZ 3/tick", "stack overflow / CPU in command completion; plugin tab-complete exceptions");
        p("resource-pack-desync", REALISTIC, MEDIUM, "pack-id validation + handshake state-machine enforcement", "CONTRADICTORY, 20/tick", "NPE on unknown pack UUID; a forged SUCCESSFULLY_LOADED bypassing a required-pack gate");
        p("sell-command-fuzz", REALISTIC, MEDIUM, "economy quantity parsing / injection hardening (belongs in Injection)", "20-tick delay", "unparsed quantity exceptions; SQL errors in the sell handler");

        // --- CONDITIONAL: needs an obtainable item / world object / triggered state ---
        p("payload-flood", CONDITIONAL, HIGH, "book/sign payload size limits at decode (sign half needs an active sign edit)", "lower page/line size first", "multi-MB string allocations per packet before any size check");
        p("container-crash", CONDITIONAL, MEDIUM, "one-container-session-per-player + container-open rate limiting", "amount 20, few containers", "orphaned container sessions; O(viewers) open-sound broadcast");
        p("lectern-crash", CONDITIONAL, MEDIUM, "lectern slot-action validation (QUICK_MOVE on a virtual slot)", "single fire", "NPE/AIOOBE in the lectern click handler");
        p("mount-crash", CONDITIONAL, HIGH, "mount state-machine atomicity + transition rate limiting", "50/tick, in a vehicle", "CME/state-machine errors during rapid mount/dismount");
        p("passenger-loop", CONDITIONAL, CRITICAL, "passenger-chain cycle guard + traversal depth bound", "20/tick, riding + looking", "a server thread spinning on an A-rides-B-rides-A passenger cycle");
        p("entity-crash", CONDITIONAL, HIGH, "vehicle-move coordinate bounds + paddle-broadcast debounce", "Position, lower amount, riding", "vehicle position not bounds-checked like player movement");
        p("portal-spam", CONDITIONAL, HIGH, "portal-transition gating (one pending transfer per player)", "50/tick, at a portal", "duplication / inventory straddling two worlds during handoff");
        p("error-crash", CONDITIONAL, MEDIUM, "click-parameter validation ordering (validate at entry, not in business logic)", "MALFORMED, 15/tick, container open", "exception stack traces per malformed click");
        p("transition-race", CONDITIONAL, CRITICAL, "in-transition packet gating during respawn/dimension reinit", "20/tick; trigger a respawn", "inventory clicks committing against half-built player state (dupe/desync)");

        // --- CREATIVE: keep and tag (real on creative-plot / build servers) ---
        p("nbt-bomb", CREATIVE, CRITICAL, "NBT depth + list-depth + fan-out caps at decode", "depth 512 LINEAR, low rate", "stack overflow / OOM allocating a pathological NBT tree");
        p("creative-crash", CREATIVE, HIGH, "block-entity-data validation vs the item's real type (entity-spawn NBT)", "15/tick", "arbitrary entity spawns from creative-slot NBT");

        // --- OPERATOR: permission-gated — tests a misconfigured server, not a normal-player threat ---
        p("structure-string-flood", OPERATOR, LOW, "string-length validation on op-gated structure/jigsaw/command-block packets", "lower length first", "multi-KB string allocations (only if the player is op with the block)");
        p("snbt-depth", OPERATOR, MEDIUM, "command-parser recursion cap (uses an op-gated @e selector)", "depth 800, 5/tick", "stack overflow in SNBT parsing (needs selector permission)");
        p("spectator-tp-edge", OPERATOR, MEDIUM, "spectator-state gating + target-resolution null-check", "RANDOM_UUID, 20/tick; be a spectator", "NPE on unknown UUID; full-world entity scans (needs spectator mode)");

        // --- THEORETICAL: real vector, not client-triggerable on current Paper (regression checks) ---
        p("sequence-crash", THEORETICAL, LOW, "sequence-number bounds (patched on Paper — mainly non-Paper servers)", "200/tick", "AIOOBE from sequence -1 (should be clamped on Paper)");
        p("message-lagger", THEORETICAL, LOW, "chat rate/length limits — NOTE: lags other CLIENTS' renderers, not the server", "length 200, no repeat", "chat flood filter; client-side render lag on recipients");

        // --- AC-bypass tests (categorised under Crash but really combat/movement AC) ---
        p("fast-mine", REALISTIC, MEDIUM, "server-side break-TIME validation (not just packet rate) — AC test", "1 cycle/tick", "diamonds breaking instantly = missing break-time check");
        p("ac-fast-use", REALISTIC, MEDIUM, "server-side item-use/cooldown enforcement — AC test", "1/tick", "instant eat / pearl spam = trusting client use timing");
        p("fast-attack", REALISTIC, MEDIUM, "server-side attack-cooldown / hit-rate enforcement — AC test", "1/tick", "multi-hit per tick = no server cooldown");

        // --- Position-churn / tracking (REALISTIC but often speed-capped on default Paper) ---
        p("chunk-border-stress", REALISTIC, MEDIUM, "cross-chunk movement rate limiting + entity-tracker debouncing (CHUNK_EDGE / TRACKING_RANGE — absorbs tracker-thrash)", "20/tick, PROBE", "chunk-load + tracker add/remove churn per crossing");
    }

    /** Profile for a module by name, or a safe UNKNOWN default. */
    public static CrashProfile get(String name) {
        return MAP.getOrDefault(name,
            CrashProfile.of(REALISM_UNKNOWN, MEDIUM, "(no profile registered)", "start low", "TPS and kick reason"));
    }

    private static final RealismTag REALISM_UNKNOWN = CONDITIONAL;
}
