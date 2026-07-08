package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.Disclosure;
import com.example.addon.audit.Severity;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;

import java.util.Locale;

/**
 * AUDIT: Command Cooldown Race (atomic-cooldown / claim-twice probe)
 *
 * Kit, warp, home, daily-reward and rankup plugins gate an action behind a cooldown.
 * If the cooldown is READ, then the reward is granted, then the cooldown is WRITTEN —
 * with a gap between read and write — two commands that arrive in the same tick can
 * BOTH pass the read before either writes, and the player claims twice (two kits, two
 * teleports, two daily rewards).
 *
 * <p>This fires the command N times in a SINGLE tick (before any cooldown can be
 * persisted) and counts how many SUCCESS responses vs COOLDOWN/denied responses come
 * back. More than one success from a single-tick burst is a non-atomic cooldown — a
 * confirmed race. It repeats the trial several times (with a gap long enough for the
 * cooldown to expire between trials) and reports how many trials double-claimed.
 *
 * <p>Distinct from command-rate-limit-probe (which tests the packet/rate limiter):
 * this tests the PLUGIN's own cooldown bookkeeping under concurrency.
 *
 * <p>Patch signal: make the cooldown check-and-set ATOMIC — write the cooldown (or a
 * per-player lock) BEFORE granting the reward, in the same synchronised section, and
 * reject the second command if the lock is held. Never grant then write.
 *
 * <p>Severity if it succeeds: HIGH → CRITICAL (free duplicated rewards / economy
 * inflation; double teleport can desync position). Works for any player with
 * permission to use the command; no op, no items.
 *
 * Run against your OWN server. Set success/cooldown keywords to match your plugin's
 * messages if the defaults miss them.
 */
public class CommandCooldownRace extends Module {
    private enum Phase { BURST, LISTEN, GAP, DONE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("Command to race (no leading slash). e.g. 'kit starter', 'warp spawn', 'home bed'.")
        .defaultValue("kit starter").build());
    private final Setting<Integer> burst = sgGeneral.add(new IntSetting.Builder()
        .name("burst").description("Copies sent in a single tick per trial.")
        .defaultValue(5).range(2, 100).sliderRange(2, 30).build());
    private final Setting<Integer> trials = sgGeneral.add(new IntSetting.Builder()
        .name("trials").description("Independent race trials (needs the cooldown to expire between them).")
        .defaultValue(3).range(1, 20).sliderRange(1, 10).build());
    private final Setting<Integer> listenTicks = sgGeneral.add(new IntSetting.Builder()
        .name("listen-ticks").description("Ticks to count responses after each burst.")
        .defaultValue(20).range(4, 100).sliderRange(6, 40).build());
    private final Setting<Integer> gapTicks = sgGeneral.add(new IntSetting.Builder()
        .name("gap-ticks").description("Ticks between trials — set above your cooldown so each trial starts fresh.")
        .defaultValue(60).range(0, 6000).sliderRange(20, 400).build());
    private final Setting<String> successWords = sgGeneral.add(new StringSetting.Builder()
        .name("success-keywords").description("Comma-separated words that indicate the reward WAS granted.")
        .defaultValue("received,claimed,given,here is,enjoy,success,teleport,whoosh,warped,welcome home,redeemed").build());
    private final Setting<String> cooldownWords = sgGeneral.add(new StringSetting.Builder()
        .name("cooldown-keywords").description("Comma-separated words that indicate a cooldown/denial.")
        .defaultValue("wait,cooldown,already,cannot,too soon,seconds,minutes,on cooldown,not yet,recently").build());
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.").defaultValue(true).build());
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print the race report on deactivate.").defaultValue(true).build());

    private int ticksActive = 0, packetsSent = 0;
    private Phase phase = Phase.DONE;
    private int trial, phaseTick;
    private int successThisTrial, cooldownThisTrial;
    private int doubleClaimTrials, cleanTrials;

    public static final Disclosure DISCLOSURE = new Disclosure(
        "kit / warp / home / daily-reward / rankup plugins that read a cooldown, grant the reward, then write the cooldown",
        "a read-grant-write cooldown with a gap between read and write lets same-tick commands both pass the read",
        "make the cooldown check-and-set atomic: write the cooldown (or a per-player lock) BEFORE granting, in one synchronised section; reject if held",
        Severity.HIGH, "any player with permission to use the command, no op");

    public CommandCooldownRace() {
        super(AddonTemplate.TESTING_CATEGORY, "command-cooldown-race",
            "Fires a kit/warp/home command many times in one tick to test whether the plugin's cooldown check-and-set is atomic (claim-twice race).");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) { error("Join a server first."); toggle(); return; }
        ticksActive = packetsSent = 0; trial = 0; phaseTick = 0;
        successThisTrial = cooldownThisTrial = 0; doubleClaimTrials = cleanTrials = 0;
        for (String l : DISCLOSURE.lines()) info("%s", l);
        info("Cooldown race: '/%s' x%d/tick, %d trial(s). Ensure gap-ticks (%d) exceeds the cooldown.",
            command.get(), burst.get(), trials.get(), gapTicks.get());
        phase = Phase.BURST; phaseTick = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || phase == Phase.DONE) return;
        ticksActive++; phaseTick++;
        switch (phase) {
            case BURST -> {
                successThisTrial = cooldownThisTrial = 0;
                for (int i = 0; i < burst.get(); i++) { mc.player.networkHandler.sendChatCommand(command.get().trim()); packetsSent++; }
                phase = Phase.LISTEN; phaseTick = 0;
            }
            case LISTEN -> {
                if (phaseTick >= listenTicks.get()) {
                    boolean doubled = successThisTrial >= 2;
                    if (doubled) { doubleClaimTrials++; warning("Trial %d: %d SUCCESS responses to a single-tick burst — DOUBLE CLAIM (non-atomic cooldown).", trial + 1, successThisTrial); }
                    else { cleanTrials++; info("Trial %d: %d success / %d cooldown — atomic (ok).", trial + 1, successThisTrial, cooldownThisTrial); }
                    phase = Phase.GAP; phaseTick = 0;
                }
            }
            case GAP -> {
                if (phaseTick >= gapTicks.get()) {
                    trial++;
                    if (trial >= trials.get()) finish();
                    else { phase = Phase.BURST; phaseTick = 0; }
                }
            }
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (phase != Phase.LISTEN) return;
        if (!(event.packet instanceof GameMessageS2CPacket m)) return;
        String text = m.content().getString().toLowerCase(Locale.ROOT);
        if (text.isBlank()) return;
        if (matchesAny(text, cooldownWords.get())) cooldownThisTrial++;
        else if (matchesAny(text, successWords.get())) successThisTrial++;
    }

    private static boolean matchesAny(String text, String csv) {
        for (String w : csv.split(",")) { String t = w.trim().toLowerCase(Locale.ROOT); if (!t.isEmpty() && text.contains(t)) return true; }
        return false;
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("=== Command Cooldown Race report ===");
            if (doubleClaimTrials > 0)
                warning("  [HIGH] %d/%d trials DOUBLE-CLAIMED — the plugin's cooldown check-and-set is not atomic. Fix: set the cooldown/lock BEFORE granting, in one synchronised section.",
                    doubleClaimTrials, trials.get());
            else
                info("  No double-claims in %d trial(s) — cooldown appears atomic (or the burst was rate-limited before the plugin saw it).", cleanTrials);
            info("Summary: %d ticks active, %d commands sent.", ticksActive, packetsSent);
        }
    }

    private void finish() { phase = Phase.DONE; onDeactivate(); if (isActive()) toggle(); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) { if (autoDisable.get() && isActive()) toggle(); }
}
