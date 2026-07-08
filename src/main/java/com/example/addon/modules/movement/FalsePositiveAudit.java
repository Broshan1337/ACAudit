package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: False-Positive Audit (does the anticheat punish LEGITIMATE play?)
 *
 * <p>Every other module in ACAudit asks "does the AC catch the cheat?" This asks the inverse,
 * which matters just as much to the AC developers using this project: does the AC wrongly setback
 * or flag a player who is doing nothing wrong, merely because their connection is imperfect? A
 * false positive on a paying player is a bug report and a refund; the classic trigger is a lag
 * spike followed by a burst of perfectly legal queued movement that a naive check reads as speed
 * or teleport.
 *
 * <p>The player moves NORMALLY (you drive; this module does not cheat). It measures the server's
 * reaction — position SETBACKS ({@link PlayerPositionLookS2CPacket}) and AC flag messages — and
 * optionally induces REALISTIC, in-spec adversity to see whether legitimate play survives it:
 * <ul>
 *   <li>{@code PASSIVE} — no interference; just count setbacks + flags while you play normally. A
 *       nonzero rate here is the AC correcting legitimate movement — investigate the tuning.</li>
 *   <li>{@code LATENCY_JITTER} — hold each outbound move a small random number of ticks (a jittery
 *       but legitimate connection), then release in order.</li>
 *   <li>{@code STALL_CATCHUP} — periodically stall outbound moves for a window, then release the
 *       queued (legal) moves in a burst — a lag-spike recovery. Setbacks right after a release are
 *       the prime false-positive signal.</li>
 * </ul>
 *
 * <p>These are CANDIDATE false positives — a setback can be a real desync — so the value is
 * quantifying the rate and correlating it with induced lag. Patch signal (for the AC): tolerate
 * legal movement that arrives late or bursty after a stall; rate-limit corrections and require
 * sustained, physically-impossible motion before a setback — not a single catch-up frame.
 * Own server only. This module sends only your own legitimate movement.
 */
public class FalsePositiveAudit extends Module {
    public enum Mode { PASSIVE, LATENCY_JITTER, STALL_CATCHUP }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Passive measurement, or induce legal latency jitter / stall-catchup.")
        .defaultValue(Mode.PASSIVE).build());
    private final Setting<Integer> jitterTicks = sgGeneral.add(new IntSetting.Builder()
        .name("jitter-ticks").description("LATENCY_JITTER: max ticks to hold each move (0..this).")
        .defaultValue(2).range(1, 10).sliderRange(1, 5)
        .visible(() -> mode.get() == Mode.LATENCY_JITTER).build());
    private final Setting<Integer> stallTicks = sgGeneral.add(new IntSetting.Builder()
        .name("stall-ticks").description("STALL_CATCHUP: ticks to stall moves before a catch-up release.")
        .defaultValue(8).range(2, 60).sliderRange(2, 20)
        .visible(() -> mode.get() == Mode.STALL_CATCHUP).build());
    private final Setting<Integer> stallEvery = sgGeneral.add(new IntSetting.Builder()
        .name("stall-every-ticks").description("STALL_CATCHUP: ticks between stall events.")
        .defaultValue(100).range(20, 1200).sliderRange(40, 400)
        .visible(() -> mode.get() == Mode.STALL_CATCHUP).build());
    private final Setting<String> flagKeywords = sgGeneral.add(new StringSetting.Builder()
        .name("flag-keywords").description("Comma-separated chat keywords that indicate an AC flag/setback message.")
        .defaultValue("flag,violation,cheat,setback,invalid move,moved too,teleport,vl ").build());

    private final List<Packet<?>> queue = new ArrayList<>();
    private PacketEvent.Send lastEvent;
    private int holdRemaining = 0, sinceStall = 0;
    private long startMs;
    private int setbacks = 0, setbacksAfterRelease = 0, flags = 0, releasedBursts = 0;
    private int postReleaseWindow = 0;

    public FalsePositiveAudit() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "false-positive-audit",
            "Inverse test: measures whether the AC setbacks/flags LEGITIMATE play (optionally under legal latency jitter / lag-spike catch-up). For AC tuning.");
    }

    @Override
    public void onActivate() {
        queue.clear();
        holdRemaining = sinceStall = 0;
        setbacks = setbacksAfterRelease = flags = releasedBursts = postReleaseWindow = 0;
        startMs = System.currentTimeMillis();
        info("Mode %s — play NORMALLY. Counting setbacks + flag messages%s.",
            mode.get(), mode.get() == Mode.PASSIVE ? "" : " while inducing legal lag");
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (mode.get() == Mode.PASSIVE) return;
        if (!(event.packet instanceof PlayerMoveC2SPacket)) return;
        // Buffer legitimate movement to release later (jitter or stall).
        queue.add(event.packet);
        lastEvent = event;
        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (postReleaseWindow > 0) postReleaseWindow--;

        switch (mode.get()) {
            case PASSIVE -> { /* measurement only */ }
            case LATENCY_JITTER -> {
                if (holdRemaining <= 0) { releaseAll(); holdRemaining = (int) (Math.random() * (jitterTicks.get() + 1)); }
                else holdRemaining--;
            }
            case STALL_CATCHUP -> {
                if (++sinceStall >= stallEvery.get() + stallTicks.get()) {
                    // end of a stall window -> catch-up burst
                    if (!queue.isEmpty()) { releaseAll(); postReleaseWindow = 10; releasedBursts++;
                        info("Catch-up: released a %d-move burst after a %d-tick stall — watch for a setback now.", queue.size() + 0, stallTicks.get()); }
                    sinceStall = 0;
                } else if (sinceStall < stallEvery.get()) {
                    // outside the stall window: pass moves through normally
                    releaseAll();
                }
                // during [stallEvery, stallEvery+stallTicks): hold (do nothing -> queue grows)
            }
        }
    }

    private void releaseAll() {
        if (queue.isEmpty()) return;
        for (Packet<?> p : queue) {
            if (lastEvent != null) lastEvent.sendSilently(p);
            else mc.player.networkHandler.getConnection().send(p);
        }
        queue.clear();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            setbacks++;
            if (postReleaseWindow > 0) { setbacksAfterRelease++;
                warning("SETBACK right after a legal catch-up burst — candidate FALSE POSITIVE."); }
        } else if (event.packet instanceof GameMessageS2CPacket m) {
            String t = m.content().getString().toLowerCase();
            for (String kw : flagKeywords.get().split(",")) {
                String k = kw.trim().toLowerCase();
                if (!k.isEmpty() && t.contains(k)) { flags++;
                    warning("AC flag message during legitimate play: \"%s\" — candidate FALSE POSITIVE.", m.content().getString());
                    break;
                }
            }
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) { if (isActive()) toggle(); }

    @Override
    public void onDeactivate() {
        releaseAll(); // never strand the player's real movement
        long secs = Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
        info("=== False-Positive report (%s, %ds) ===", mode.get(), secs);
        info("  Position setbacks: %d (%.1f/min)", setbacks, setbacks * 60.0 / secs);
        if (releasedBursts > 0) info("  Catch-up bursts: %d, setbacks within their window: %d.", releasedBursts, setbacksAfterRelease);
        info("  AC flag messages: %d (%.1f/min)", flags, flags * 60.0 / secs);
        if (setbacks == 0 && flags == 0) info("  Clean — no false-positive signals under this condition (GOOD).");
        else warning("  Investigate: the AC reacted to legitimate play. Correlate with the induced lag before treating as a bug.");
    }
}
