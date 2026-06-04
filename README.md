# ACAudit

A [Meteor Client](https://meteorclient.com/) addon for **auditing your own Minecraft server's anti-cheat and stability** on Minecraft **1.21.11**.

ACAudit bundles the movement cheats, dupe/economy probes, malformed-packet stress tests, and a resilience test harness that a server operator needs to answer one question: *does my anti-cheat actually catch what it claims to, and does my server stay up under abusive input?* Every module is paired with the detection or hardening signal it is meant to expose, so a failed catch points directly at the fix.

---

## ⚠️ Authorized use only

**Use this addon only against servers you own, or servers whose owner has given you explicit, informed consent to test.**

- Running these modules against a server you do not own, or without the operator's permission, is a denial-of-service / unauthorized-access attempt and is **illegal in most jurisdictions** and a violation of essentially every server's rules and the Minecraft EULA.
- The crash, flood, and dupe modules can degrade or take down a server and can corrupt data. Test on a **local or staging instance** first, never on production with players online.

## Disclaimer of liability

This software is provided **"as is", without warranty of any kind**, express or implied. The author(s) accept **no liability** for any damage, data loss, downtime, account action, legal consequence, or other harm arising from the use or misuse of this addon. **You alone are responsible for how you use it**, and by using it you accept full responsibility for ensuring you have authorization to test the target server.

This is a defensive security / QA tool. It is not intended to be used to cheat on, attack, or disrupt servers you do not control.

---

## Why I made this

I run my own Minecraft server with a custom anti-cheat and custom economy/dupe protections. To trust that protection I needed to attack it the way a real cheater would — fly, speed, packet-level evasion, inventory dupes, economy value fuzzing — and to confirm the server survives malformed and high-volume input rather than crashing or hanging.

Existing cheat clients (LiquidBounce, etc.) and crash addons aren't built for auditing: they're built to win, they're scattered across projects, and they tell you nothing about *why* a bypass worked or *how* to close it. ACAudit reimplements the techniques as labeled test vectors inside one addon, each documented with the heuristic weakness it exploits and the server-side invariant that defeats it — so it's a tool for **hardening** a server, not breaking other people's.

---

## Features

Modules are organized into four category tabs: Movement, Dupe, Crash, and Testing.

###  AuditAC-Movement — bypass & evasion vectors
Tests whether your AC catches illegal movement and, crucially, whether a cheater can stop it from ever *seeing* the cheat.

| Module | Tests |
|---|---|
| `low-hop-fly` | `onGround=true` while hovering — server-side ground validation |
| `omni-sprint` | sprint in all directions — sprint vs. movement-vector cross-check |
| `velocity-exploit` | cancels knockback packets — server-side knockback authority |
| `packet-step` | Y-spoof step without a jump arc — path-continuity validation |
| `ac-high-jump` | multiplied jump velocity — vertical-speed / jump-height checks |
| `ac-air-jump` | jump again mid-air — fly / air-movement detection |
| `bhop` | re-jump with no landing delay — jump-delay & ground-state tracking |
| `speed` | strafe velocity boost — horizontal-speed detection |
| `instant-step` | full-block step via a jump-arc packet sequence — vertical-teleport checks |
| `stealth-fly` | 4 modes (sub-threshold / static-hover / glide / jitter) that stay under threshold checks — probes per-tick-delta, ground-flag, and gravity-sim blind spots |
| `ac-blink` | holds outbound packets then bursts them (lag switch) — packet-cadence / silence detection |
| `ping-spoof` | delays KeepAlive responses to fake high ping — whether latency checks are client-gameable |
| `ac-no-fall` | spoofs ground while falling — server-side fall-damage computation |
| `reset-vl` | clean filler hops — whether violation-level decay is farmable |
| `anti-setback` | rejects server position corrections — whether enforcement survives a non-cooperative client |
| `ac-timer` | speeds the client tick clock (mixin) — wall-clock vs. per-tick rate limiting |
| `ac-no-slow` | removes item-use slowdown (mixin) — server-side speed re-derivation from item-use state |
| `vehicle-move` | flies/speeds the ridden vehicle via `VehicleMoveC2SPacket` — whether vehicle movement gets the same authority as player movement (boat-fly/boat-speed blind spot) |
| `elytra-exploit` | overrides glide velocity to a fixed speed (firework-boost spoof) — server-side elytra speed envelope |
| `riptide-launch` | imparts a riptide-magnitude impulse while dry — whether riptide velocity is gated on the water/rain precondition |
| `phase` | walks reported position through blocks then snaps across — move-continuity / collision validation (`clamp-legal-steps` makes it the pure "each delta legal, summed path crosses solid" test) |

#### Deep-coverage probes — physics, AC-model, and intent

A basic anti-cheat **measures values**; a good one **models physics**; a great one **models intent**. These vectors target the gap between those layers: movement that looks legitimate in every individual packet but could never have been produced by a real player in a real physics simulation. Every probe is graded by a shared **`MovementObserver`** that reports what the server actually did — **setback** (caught it), **silent-accept** (didn't model it — the dangerous case), or **kick** — plus correction magnitude and min-TPS, so a pass means the AC genuinely modeled the exploit, not that it caught the obvious case.

| Module | Tests |
|---|---|
| `momentum-break` | a move sequence with legal per-step speed but impossible acceleration between steps — whether the AC models **acceleration**, not just speed |
| `ground-state-forge` | `onGround` transitions inconsistent with the Y-trajectory (fake-landing / no-approach / flicker) — whether the AC **derives** ground state instead of trusting the client bit |
| `jump-arc-forge` | a jump with correct peak height but physically wrong arc shape/timing/symmetry — whether the AC validates the **whole trajectory** against gravity |
| `model-drift` | a tiny constant sub-threshold position bias every tick — whether the AC bounds **cumulative** drift or only per-tick error |
| `block-update-race` | a block place/break and a move on the same tick — whether movement validates against a **consistent world snapshot** |
| `chunk-edge-move` | oscillates across the nearest chunk seam with borderline steps — a **validation gap at chunk boundaries** |
| `legit-velocity-launder` | latches a legitimate velocity source (KB/ice/current/piston) and retains it past natural decay — whether the AC models velocity **decay**, not just its source (also the ice false-positive test) |
| `physics-anomaly` | 7 borrowed-physics trajectories (gravity / step-stack / swim / slow-fall / levitation / vehicle / scaffold) the player isn't actually in — **state-gated** physics validation |
| `transaction-timing` | delays the vanilla **transaction** (`CommonPing`/`Pong`) replies — the clock transaction-based latency compensation actually reads (unlike `ping-spoof`'s keepalive path) — to inflate the latency the AC measures and widen its tolerance |
| `state-machine-fuzz` | cycles ground/air transitions faster than the jump cooldown allows, each transition individually valid — **transition-rate** limiting |
| `packet-order-skew` | reorders the tick's move and action packets (action-first / interleaved) — whether the AC assumes a fixed **per-tick packet order** |
| `combat-state-probe` | one attack while in an impossible movement state (jump apex / fast move / sprint desync) — **combat↔movement state sharing** (a minimal probe, not a kill-aura) |

Several existing modules also gained deep-coverage modes: `speed` (`adaptive-seek` finds the exact detection threshold), `ac-high-jump` (ramp + observer = adaptive threshold-seeking), `ac-timer` (`clock-drift`: an undetectable sub-1% sustained drift that accumulates over a minute), `reset-vl` (`interleave-violation`: slips a real violation among the filler and reports whether VL ever flags it), `anti-setback` (`reassert-position`: leaves the AC's model inconsistent), `elytra-exploit` (`transition-carry`: carries glide momentum into on-foot movement), and `ping-spoof` (`realistic-latency`: organic jitter/spikes/drift instead of a flat delay).

#### Modern / predictive-AC deep coverage — architecture, not values

The probes above test whether an AC models physics. These test the **architecture** modern anti-cheats are built on — *generically*, against any server (vanilla/Bukkit/Spigot/Paper) and any AC (Grim, Vulcan, Verus, Matrix, Spartan, AAC, custom). They target the surfaces a *correct* design must defend: **prediction/simulation tolerance budgets**, **transaction-based latency compensation**, **block-physics simulation gaps**, **check interaction**, **long-session integrity**, and **deliberately-tolerated false positives**. Every patch signal below describes what *any* well-implemented server/AC should do — not what one product does.

Three shared helpers back them: **`TransactionAnchor`** (reads the server's `CommonPing`/`Pong` transaction cadence as a precise acknowledged-tick boundary and can inject a measured Pong delay to probe the compensation window — honest about what a client cannot do: it can't initiate a transaction or read the server-side RTT), **`OffsetSeeker`** (drives any value up in steps until the server first corrects, reporting the exact tolerated boundary), and **`BaselineProfiler`** (clean-baseline-then-gradual-ramp sequencer for the profile/heuristic AC class).

| Module | Tests | Patch signal (what any well-implemented AC should do) |
|---|---|---|
| `uncertainty-farm` | waits for a **real** uncertainty source (push / slime / ice) then spends a small illegal delta inside the borrowed tolerance window | scope every tolerance grant to its source and decay it per-tick — knockback slack must not pay for a speed delta |
| `offset-boundary` | maps the exact horizontal-offset the AC tolerates in the player's current state (ground/air/water/ice/sprint) — an instrument, not a bypass | the located boundary should equal, not exceed, the legal simulation envelope for that state |
| `input-launder` | reports a delta shaped like a legal input the player isn't pressing (sprint while idle, jump while grounded) | flag when the only input that explains the motion contradicts the client's claimed held keys |
| `compensation-boundary` | holds a fixed illegal delta and **ramps injected transaction latency** until the compensation window absorbs it | hard-cap the window and cross-check transaction latency against an independent RTT |
| `sim-gap-suite` | borrows special-case **block physics** (powder snow / bubble columns / cobweb / honey / edge-vs-centre landing / scaffold) without the block present | gate every special-case physics rule behind the authoritative block at that position |
| `entity-push-model` | spends a small delta only while a **real entity crowd** is pushing you | bound push tolerance by the actual entities present and their max push, never open slack for "being in a crowd" |
| `reach-world-state-race` | fires one attack on the exact tick the target's position/velocity update lands — the one genuine concurrency surface | compute reach against the compensated position **history** at the acknowledged tick, never the live target position |
| `setback-interference` | provokes a setback then fires a second illegal hop inside the pending-setback window | apply the pending setback to **every** dependent check's baseline atomically; ignore movement until the teleport confirms |
| `timer-balance-soak` | inflates flying-packet rate by a sub-1% drift over a **long session** | reconcile the timer balance to an authoritative clock with bounded, symmetric decay |
| `accumulator-soak` | emits one identical violation at a fixed cadence all session, watching whether the correction rate **drifts** | response to an identical violation must be invariant to session length and packet count |
| `fp-cover` | adds a minimal illegal delta while you perform a **documented false-positive** scenario (elytra-wall, ice-edge, vehicle desync, near-void, knockback-lag, piston, riptide-rain) | an FP exemption must be scoped so the legit scenario passes but scenario-plus-delta still flags |
| `source-attribution` | holds a fixed speed; run with and without a real velocity source and compare | identical speed must be accepted **with** a source and corrected **without** one |
| `low-tps-reach` | attacks the nearest target beyond normal reach and records distance vs **TPS** (pair with a Crash-tab load module) | reach leniency under low TPS must be bounded; max accepted reach stays near vanilla regardless of lag |
| `baseline-poison` | behaves cleanly to establish a baseline, then **drifts** a delta in as a continuation — probes the profile/heuristic class (labeled: not deterministic-physics ACs) | anchor the behavioral baseline to physically legal bounds, not the player's own movable history |
| `vehicle-sim-gap` | vehicle-specific gaps — ice overspeed, passenger↔vehicle position desync (counts `VehicleMoveS2C` corrections) | apply the same authority to vehicle movement as to player movement, and reconcile passenger position against its vehicle |

**Honest scope on the older modules:** `ping-spoof` (keepalive-delay), `anti-setback` (drops the correction), and `packet-order-skew` (reorders a tick's packets) each now carry a **SCOPE** note stating which architecture they bite and where they're inert — keepalive-based, client-trusted-setback, and packet-reordering ACs respectively. Against a transaction-based, server-authoritative, in-order AC a *null* result from these **is the pass**, and the new modules above cover those architectures directly.

###  AuditAC-Dupe — duplication & economy probes
Tests inventory/container atomicity and economy-plugin input handling.

| Module | Tests |
|---|---|
| `slot-exploit` | OOB / negative slot clicks + stale revision — bounds & revision validation |
| `interaction-flood` | many clicks per tick on one slot — race-condition dupe windows, rate limiting |
| `drop-pickup-dupe` | THROW+PICKUP with stale revision — inventory reconciliation |
| `container-exploit` | post-close clicks & wrong syncId — container state validation |
| `econ-fuzz` | fuzzes an economy command with edge-case numbers (scientific notation, negatives, overflow, injection probes) — value parsing |
| `sell-race` | same-tick command burst (TOCTOU) — atomicity of sell/payout vs. item removal |
| `auction-race` | same-tick valid clicks on a GUI slot — concurrency handling of buy/claim/list buttons |
| `gui-clicker` | hold a keybind to spam-click the slot **under your cursor** in any open GUI — plugin-GUI click concurrency |
| `manual-click` | hand-crafts a `ClickSlotC2SPacket` (syncId / revision / slot / button / action) — bring-your-own-payload interaction tester |
| `two-window-race` | clicks a container slot and a player-inventory slot in the same tick — cross-inventory locking in trade/sell GUIs |
| `close-click` | sends a click and the window-close packet in the same tick — synchronous, atomic window teardown |
| `slot-overlay` | renders each slot's network id on top of the open GUI — maps container / plugin layouts so you know which id to target |
| `relog-dupe` | runs an action then force-disconnects N ticks later — save-on-quit ordering vs. in-flight transactions (the combat-log dupe) |
| `enderchest-desync` | grabs ender-chest contents then force-disconnects — atomic+flushed profile save and session fencing |
| `crafter-dupe` | floods Crafter (1.21) grid slot-toggle clicks while it crafts — craft-vs-toggle atomicity (run with a redstone clock) |
| `craft-grid-race` | same-tick `QUICK_MOVE` burst on the crafting result slot — result-vs-grid-consumption atomicity |
| `bundle-dupe` | stale-revision `PICKUP` burst on a bundle slot — bundle component/count reconciliation |
| `portal-dupe` | fires an action on an interval and logs dimension changes — transaction handoff across dimension boundaries |
| `anvil-grindstone-race` | same-tick `QUICK_MOVE` burst on the anvil/grindstone output slot — output-vs-input+XP atomicity |
| `shift-click-race` | same-tick `QUICK_MOVE` burst across a slot range — `QUICK_MOVE` destination-search atomicity |
| `drag-split-race` | starts a right-click drag then closes the container mid-drag — drag-state cleanup on window close |
| `phantom-container` | closes a container then immediately clicks the same syncId — post-close click rejection |
| `death-inventory-race` | floods inventory ops when health is critically low — death-processing vs. inventory-mutation atomicity |
| `chest-shop-race` | spams shop block-use + container clicks simultaneously — shop buy-trigger vs. click atomicity |
| `hopper-race` | floods clicks on hopper slots while the hopper transfer tick runs — hopper transfer vs. player-click mutex |

###  AuditAC-Crash — malformed & high-volume stress tests
Tests whether bad or excessive input produces a clean reject/flag instead of a thread hang or crash. **Run against your own local server only.**

`payload-flood`, `nbt-bomb`, `nan-position`, `extreme-velocity`, `block-interaction-spam`, `arm-animation-flood`, `sell-command-fuzz`, `position-crash`, `book-crash`, `completion-crash`, `container-crash`, `creative-crash`, `entity-crash`, `error-crash`, `interact-crash`, `lectern-crash`, `message-lagger`, `movement-crash`, `packet-spammer`, `sequence-crash`, `window-crash`.

Newer additions:

| Module | Tests |
|---|---|
| `snbt-depth` | sends commands with deeply-nested SNBT in a selector — command-parser recursion/size limits (distinct from `nbt-bomb`'s item NBT) |
| `structure-string-flood` | floods structure/jigsaw/command-block packets with oversized string fields — string length validation on those less-trodden inputs |
| `beacon-crash` | floods beacon-effect update packets with no beacon open — beacon update gating + rate limiting |
| `passenger-loop` | spams mount interactions to provoke a passenger cycle (A rides B rides A) — passenger-chain cycle guard / traversal bounds |
| `chunk-border-stress` | ping-pongs reported position across a chunk boundary rapidly — chunk-load / entity-tracking pipeline under crossing pressure |
| `portal-spam` | spams portal block-use + position packets into the portal — portal transition pipeline under rapid re-initiation |
| `entity-spam` | spams attack/interact + arm-swing packets on the crosshair entity — entity interaction rate-limiting and combat cooldown enforcement |
| `mount-crash` | rapid mount/dismount packet spam — mount state-machine atomicity and transition rate limiting |
| `channel-flood` | floods sprint-toggle / held-slot / offhand-swap metadata packets — per-type metadata rate limiting |
| `packet-order-chaos` | sends packets in causally impossible sequences — server-side precondition validation and causal-order enforcement |

#### Fast-action rate testers (in AuditAC-Crash)
Test whether action **rate and timing** are enforced server-side, not just trusted from the client. The key insight: a packet-rate limiter catches the blunt version, but the real check is server-side **time/cooldown validation** — a single action that arrives faster than physics allows is still illegal.

| Module | Tests |
|---|---|
| `fast-mine` | instant-breaks the looked-at block via START/STOP packets — server-side break-**time** validation (`elapsed ≥ hardness/toolSpeed`), not just packet rate |
| `ac-fast-use` | item use faster than vanilla allows — use / cooldown enforcement (eat, pearl, potion) |
| `fast-attack` | many attacks per tick on the looked-at entity — attack-cooldown / hit-rate enforcement |

### AuditAC-Testing — diagnostics, automated harness & experimental abuse
Measurement and orchestration for resilience testing, plus the sneakier packet-timing vectors. **Run against your own local server only.**

**Diagnostics & harness**
| Module | Purpose |
|---|---|
| `server-probe` | live read-only readout: TPS · ping · setbacks/sec · in/out packets/sec · last kick reason — watch the server react in real time |
| `server-health-monitor` | passive TPS (time-update cadence) + ping estimator — measurement source for the harness |
| `soak-test` | drives one load module for a fixed window, reports **PASS/FAIL** vs. TPS floor / ping ceiling / disconnect |
| `stress-runner` | runs each named load module in sequence, prints a per-vector **PASS/FAIL** table — automated resilience sweep |
| `lag-profiler` | **ramps** one packet vector's rate and reports the rate that first drops TPS below the floor — finds the breaking point |

**Experimental packet abuse**
| Module | Tests |
|---|---|
| `typed-blink` | holds one packet category (movement/container/combat) while sending the rest — the **lagback** exploit; position-authority for combat |
| `packet-reorder` | buffers movement/action packets and releases them reversed or shuffled — causal-order validation |
| `confirm-desync` | withholds teleport-confirm packets — how the server handles an unacknowledged setback |
| `metadata-flood` | floods legal metadata packets (sprint-toggle / held-slot / client-options) that are cheap to send but force **O(viewers) rebroadcast** — CPU exhaustion + Netty backpressure |

(`ping-spoof` in AuditAC-Movement also has a **`max-window`** mode — keepalive starvation that holds responses ~29s, just under the timeout, for the maximum lag window.)

**New monitoring modules**
| Module | Purpose |
|---|---|
| `check-rate-monitor` | counts setback packets/s split by moving vs. still — fingerprints the AC's check cadence |
| `correction-timing-monitor` | measures RTT from sending movement to receiving a correction — AC response latency in ms and ticks |
| `packet-cadence-monitor` | counts S2C packets by type and reports top-N/s — establishes baseline server traffic patterns |
| `combat-pattern-monitor` | logs CPS, swing-to-attack ratio, multi-attack ticks — combat baseline for AC calibration |
| `command-rate-limit-probe` | sends command variants (normal / namespaced / mixed-case / aliased) and watches for rate-limit responses |

**New automation modules**
| Module | Purpose |
|---|---|
| `auto-audit-runner` | sequential module sweep with per-vector PASS/FAIL; saves timestamped report to `config/acaudit/reports/` |
| `combo-test` | activates two named modules simultaneously and reports combined TPS impact — compound-vector stress |
| `vector-matrix` | runs the full crash vector list in sequence and saves a PASS/FAIL matrix to `config/acaudit/reports/` |

Most modules include an **`auto-disable`** option (on by default) that switches the module off when you are kicked or disconnect, so a failed test doesn't keep firing on reconnect.

All modules now include a **`show-stats`** checkbox (on by default). When the module is deactivated, it prints how many ticks it was active and how many packets it sent — useful for confirming the module actually fired and quantifying the load applied.

---

## Building

Requires JDK 21 (the build targets Java 21 bytecode).

```bash
./gradlew build
```

Output: `build/libs/acaudit-0.1.0.jar`. Drop it into your `mods` folder alongside Meteor Client for 1.21.11.

**Stack:** Minecraft 1.21.11 · Meteor `1.21.11-SNAPSHOT` · Fabric Loader 0.18.2 · Loom 1.14 · Yarn `1.21.11+build.3` (v2) · Gradle 9.2.0.

---

## Notes

- Meteor ships its own `Timer`, `Blink`, `NoFall`, `NoSlow`, `HighJump`, `AirJump` modules; ACAudit's equivalents are prefixed `ac-` so they coexist without colliding with Meteor's registry.
- Movement/evasion techniques are reimplemented from the *technique* (e.g. LiquidBounce), not copied — every one is documented in-source with the detection signal that defeats it.
- This addon deliberately does **not** include exploits against third-party plugins, remote-code-execution vectors, or anti-cheat "disablers" for commercial products — those are weapons against other people's software, not tests of your own server.
