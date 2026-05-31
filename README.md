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

Modules are organized into three category tabs plus a test harness.

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

###  AuditAC-Crash — malformed & high-volume stress tests
Tests whether bad or excessive input produces a clean reject/flag instead of a thread hang or crash. **Run against your own local server only.**

`payload-flood`, `nbt-bomb`, `nan-position`, `extreme-velocity`, `block-interaction-spam`, `arm-animation-flood`, `sell-command-fuzz`, `position-crash`, `book-crash`, `completion-crash`, `container-crash`, `creative-crash`, `entity-crash`, `error-crash`, `interact-crash`, `lectern-crash`, `message-lagger`, `movement-crash`, `packet-spammer`, `sequence-crash`, `window-crash`.

#### Fast-action rate testers (in AuditAC-Crash)
Test whether action **rate and timing** are enforced server-side, not just trusted from the client. The key insight: a packet-rate limiter catches the blunt version, but the real check is server-side **time/cooldown validation** — a single action that arrives faster than physics allows is still illegal.

| Module | Tests |
|---|---|
| `fast-mine` | instant-breaks the looked-at block via START/STOP packets — server-side break-**time** validation (`elapsed ≥ hardness/toolSpeed`), not just packet rate |
| `ac-fast-use` | item use faster than vanilla allows — use / cooldown enforcement (eat, pearl, potion) |
| `fast-attack` | many attacks per tick on the looked-at entity — attack-cooldown / hit-rate enforcement |

###  Test harness (in AuditAC-Crash)
| Module | Purpose |
|---|---|
| `server-health-monitor` | passively estimates server TPS (from time-update cadence) and ping — the measurement source |
| `soak-test` | drives a chosen load module for a fixed window and reports **PASS/FAIL** against a TPS floor / ping ceiling / disconnect — resilience regression harness |

Most modules include an **`auto-disable`** option (on by default) that switches the module off when you are kicked or disconnect, so a failed test doesn't keep firing on reconnect.

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
