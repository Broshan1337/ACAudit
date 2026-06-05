# ACAudit — Local Testing Guide

ACAudit is a **client**: it sends packets and observes what comes back. For most vectors the *meaningful* result is on the **server side** — in your anticheat's violation log, your economy plugin's transaction log, or the server console. This guide tells you what to watch, which modules need careful observation, and which need a richer setup than one local box.

The golden rule: **run `platform-probe` first** so you know what you're testing (Paper vs Spigot vs Folia, and whether the packet-limiter is active), and **keep `server-health-monitor` on** during everything so TPS context is always recorded. The `auto-audit-runner` in **GUIDED** mode does both for you and runs a sensible order (quiet probes first, crash modules last).

## Reading the output

- **Movement modules** grade via `MovementObserver`: `setback` (the AC corrected you), `silent-accept` (no correction in the window), or `kick`. **`silent-accept` only means "the AC missed it" if an AC is actually running.** Set `ac-present` correctly: with it OFF, the report tells you silent-accept is *expected* (nothing was judging the movement). On a no-AC server every movement module reports silent-accept — that is not a finding.
- **Dupe modules** grade via `DupeObserver`: it infers dup/loss from item-delta and confirm/resync packets, but you should **independently verify** item counts / economy balances before and after.
- **Crash modules** grade via `GracefulResponse`: `REJECTED` (+kick reason), `SILENT_DROP`, `CORRECTED`, plus min-TPS. A clean `min TPS held` + graceful reject is a pass.

## What to watch in server logs, by category

| Category | Watch in the server for… |
|---|---|
| **Movement** | Your AC's violation log (e.g. Grim `Offset`/setback lines, your custom AC's flags); Paper console `moved too quickly` / `moved wrongly`; whether a setback teleport actually applied server-side. |
| **Dupe** | Economy/transaction logs; an item-count or balance audit before vs after; any Paper dupe-prevention warnings; did total items/money change. |
| **Crash** | Console **exceptions / stack traces**; watchdog `Can't keep up` / TPS (spark or timings); **packet-limiter kick lines** (paper-global.yml); OOM. |
| **Proxy** (Bungee/Velocity) | Proxy console for how it handled Connect/Forward/PlayerCount/cookie messages; "received plugin message" handling. |

## Modules that need careful observation (not just run-and-check)

These produce their real signal off-client — watch the logs above while they run:
- All movement-observer modules (the AC's decision is server-side).
- All dupe modules (verify counts/balances yourself).
- `uncertainty-farm`, `fp-cover`, `sim-gap-suite` — only meaningful while you're **actually in** the real scenario (on slime/ice, gliding, etc.).
- `packet-limiter-map` — for silent throttling use **TAB_COMPLETE** mode (it tracks response rate); a kick-less throttle is reported as "SILENT THROTTLE".

## Modules that need a 2nd player or a richer setup (flag before "production ready")

| Module(s) | Needs |
|---|---|
| `reach-world-state-race`, `combat-state-probe`, `low-tps-reach` | A target entity; a real **player** target is the meaningful AC reach test. |
| `entity-push-model` | A crowd of entities/players around you. |
| `vault-value-probe` | A real **second account** you control (the transfer recipient). |
| `plugin-message-probe`, `cookie-probe` | An actual **BungeeCord/Velocity proxy + ≥2 backend servers**. |
| `folia-cross-region` | A **Folia** server (on standard Paper it is a near-no-op by design). |
| `trade-shop-overlap`, `chest-shop-race`, villager/trade dupes | A trade partner / second party. |

Everything else is fully exercisable on one local Paper box with your anticheat installed.

## Multi-vector: combinations that single-module testing will never reveal

Real attackers hit many surfaces at once. Use **`combo-orchestrator`** (3+ vectors, per-vector start offset, conflict warnings, combined-outcome reporting). The combinations most worth running:

| Combination | Why it only shows together |
|---|---|
| `anti-setback` + `speed` (or `phase`) | anti-setback alone does nothing; speed alone gets set back. Together = does dropping the correction become a bypass. |
| any dupe race (`shift-click-race`, …) + a lag inducer (`packet-spammer` / `movement-crash`) | server lag widens the race window — "duped only under load". |
| `transaction-timing` (or `compensation-boundary`) + a movement cheat | inflated latency only matters paired with a flaggable move. |
| `econ-fuzz` + `interaction-flood` | economy parsing under event-queue pressure. |
| `low-tps-reach` + a lag inducer | reach leniency only opens once TPS actually drops. |

**Conflicts:** two velocity-writing vectors (e.g. `speed` + `bhop`) overwrite each other's velocity, and two packet-holders (e.g. `ac-blink` + `ping-spoof`) interfere on the send pipeline — `combo-orchestrator` detects these, warns, and suggests a non-conflicting substitute. Pair a velocity vector with a *packet/dupe/crash* vector, not another velocity vector.

## What a Grim dev / Paper contributor wants to see

When reporting a finding to an AC or platform dev, include, per vector:
1. The exact **server-side signal** it should produce (which check fired, with what offset / which Paper log line).
2. The **patch signal** (every module's docstring has one — "what any well-implemented AC/server should do").
3. **Reproducibility**: the module + its settings, and ideally the packet sequence sent, so they can diff it against their own logs.

## Recommended first audit

1. `platform-probe` (identify the stack) → leave running.
2. `server-health-monitor` on → leave running.
3. A representative movement set (with `ac-present` set correctly) — read your AC log.
4. A dupe set — audit balances/counts.
5. Crash set **last** (these can kick/lag).
6. A few `combo-orchestrator` runs from the table above.

`auto-audit-runner` (GUIDED) automates steps 1–5 and writes a timestamped report to `config/acaudit/reports/`.

## The pipeline (auto-audit-runner) — what it now does for you

You no longer have to read raw chat to interpret a run. `auto-audit-runner` **grades** every vector from the packet stream and emits a **severity-classified report** in three formats:

- **`.txt`** to read, **`.md`** to paste into a GitHub issue / Discord report, **`.json`** for tooling and regression.
- **Severity:** CRITICAL (crash / confirmed dupe) · HIGH (AC or economy bypass) · MEDIUM (degraded / ambiguous) · LOW/INFO (context). Every report embeds the **platform** (brand + classification) so a dev reading it knows the stack.
- **Modes:** **GUIDED** (recommended first audit), **QUICK** (<5-min subset), **CUSTOM** (your ordered list; join names with `+` to run them **simultaneously**, conflict-checked).
- **Dry-run** prints/saves the plan and fires nothing. **Skip** toggles drop a whole category (e.g. skip-crash on a production-like box). The **safety gate** refuses a non-local server unless you tick *i-own-this-server*.
- **Crash safety:** gaps are **recovery-gated** (wait for TPS to climb back before the next vector), recovery time is measured, and outcomes are split into **crash vs lag-spike vs hang**. **safe-mode** shortens windows and excludes the hardest crashers.
- **Auto-rejoin:** a kick records the reason, then the runner **rejoins, waits for stabilisation, and resumes at the next vector** instead of aborting the whole run.
- **Regression:** with *compare-to-last* on, the run **diffs against the previous report** and flags NEW / REGRESSED / IMPROVED / RESOLVED per vector — run it after a Grim or plugin update.

## Named scenarios (audit-scenarios)

One-click playbooks that answer a specific question and emit their own severity report:

- **econ-integrity** — reads your balance, runs the economy/dupe vectors, reads balance again, reports the **net change** (and flags a non-finite / absurd jump as a confirmed break). Set `balance-command` to whatever your economy plugin uses.
- **AC-coverage-map** — runs each movement vector and maps **DETECTED / CORRECTED / UNDETECTED** from the AC's chat/action-bar/title/kick signals → a coverage map for *this* anti-cheat.
- **stability-baseline** — runs the crash set at short, recovery-gated windows and records min TPS + recovery per vector → a stability profile to compare over time.
- **plugin-fingerprint** — drives the fingerprinting probes and captures the platform context.
- **combo-matrix** — baselines each vector in a set alone, then runs **every pair** and flags the pairs whose outcome differs from either alone (synergistic surfaces).
