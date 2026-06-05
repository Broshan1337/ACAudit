package com.example.addon.modules.antidupe;

import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.ScreenHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Shared duplication-outcome observer (review axis 8).
 *
 * A dupe vector "passing" should mean the plugin genuinely handled that timing
 * window or state transition — verified, not assumed. This composition helper
 * grades what the server actually did after a fire:
 *
 *   CONFIRMED   — server applied it (ScreenHandlerSlotUpdate for the action).
 *   RESYNCED    — server rejected/corrected it (InventoryS2CPacket full resync).
 *   SILENT      — fired, but no echo within the window: the packet path mutated
 *                 (or ignored) state with no correction — the dangerous case, and
 *                 the strongest client-side signal that a plugin's InventoryClick
 *                 validation was bypassed (we cannot see Bukkit events directly,
 *                 so silent-accept + nonzero item delta is the documented proxy).
 *   ITEM DELTA  — snapshots item totals across the open container + player
 *                 inventory before vs. after; reports DUPLICATED / LOST / conserved.
 *   BALANCE     — counts economy/chat messages since the fire (none / once / many)
 *                 so a balance that changed twice is visible.
 *   REPEAT      — fire/settle/fire and diff: stateful-but-incomplete protection.
 *   UNDER LOAD  — pair with crash.PreStress to compare degraded vs. baseline.
 *
 * Composition, not inheritance: the owning module forwards onActivate / tick /
 * markFired / onReceive and prints report().
 */
public final class DupeObserver {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final Setting<Boolean> observe;
    private final Setting<Integer> window;
    private final Setting<Boolean> logResponses;

    private boolean kicked;
    private String kickReason;
    private int fires, confirms, resyncs, silent, messages;
    private int ticksSinceFire = -1;
    private boolean awaitingEcho;

    private Map<Item, Integer> preSnapshot;
    private int dupEvents, lossEvents;

    private final List<String> recentMessages = new ArrayList<>();

    private ServerHealthMonitor health;
    private double minTps = 20.0;
    private boolean sawTps;

    public DupeObserver(SettingGroup g) {
        observe = g.add(new BoolSetting.Builder()
            .name("observe-outcome")
            .description("Grade what the server did: confirm vs. resync vs. silent, item duplicated/lost, balance changes, min TPS.")
            .defaultValue(true).build()
        );
        window = g.add(new IntSetting.Builder()
            .name("outcome-window-ticks")
            .description("Ticks to wait after a fire for the server's echo before classifying it.")
            .defaultValue(10).range(2, 200).sliderRange(2, 60)
            .visible(observe::get).build()
        );
        logResponses = g.add(new BoolSetting.Builder()
            .name("log-responses")
            .description("Print a live survey of each server response (slot update / resync / chat message) as it arrives.")
            .defaultValue(true).build()
        );
    }

    public boolean enabled() { return observe.get(); }
    public boolean kicked()  { return kicked; }

    public void onActivate() {
        kicked = false; kickReason = null;
        fires = confirms = resyncs = silent = messages = 0;
        ticksSinceFire = -1; awaitingEcho = false;
        preSnapshot = null; dupEvents = lossEvents = 0;
        recentMessages.clear();
        health = null; minTps = 20.0; sawTps = false;
    }

    /** Snapshot inventory totals and mark a fire. Call right as the vector fires. */
    public void markFired() {
        fires++;
        preSnapshot = snapshot();
        ticksSinceFire = 0;
        awaitingEcho = true;
    }

    public void tick() {
        if (!observe.get()) return;
        if (ticksSinceFire >= 0) ticksSinceFire++;

        // close the outcome window: classify silent + diff item totals
        if (awaitingEcho && ticksSinceFire >= window.get()) {
            awaitingEcho = false;
            if (confirms == 0 && resyncs == 0) silent++;
            diffSnapshot();
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
        } else if (packet instanceof ScreenHandlerSlotUpdateS2CPacket || packet instanceof PlayerPositionLookS2CPacket) {
            confirms++;
        } else if (packet instanceof InventoryS2CPacket) {
            resyncs++;
        } else if (packet instanceof GameMessageS2CPacket m) {
            String t = m.content().getString();
            if (!t.isBlank()) {
                messages++;
                if (recentMessages.size() < 6) recentMessages.add(t);
            }
        }
    }

    public void onKick() { kicked = true; }

    /**
     * Feed a received packet AND print a live, human-readable survey line for the
     * server responses that matter to a dupe audit (slot update, full resync, chat
     * message). Modules call this from onReceivePacket instead of plain onReceive()
     * so every vector gives the same real-time view of what the server did — not
     * just the end-of-run summary. Gated by the "log-responses" setting.
     */
    public void survey(Object packet, Consumer<String> printer) {
        onReceive(packet);
        if (!observe.get() || !logResponses.get()) return;
        if (packet instanceof ScreenHandlerSlotUpdateS2CPacket p)
            printer.accept(String.format("Server updated slot %d → %s (syncId %d)",
                p.getSlot(), p.getStack().getName().getString(), p.getSyncId()));
        else if (packet instanceof InventoryS2CPacket p)
            printer.accept(String.format("Server resynced inventory (syncId %d, %d slots)",
                p.syncId(), p.contents().size()));
        else if (packet instanceof GameMessageS2CPacket m) {
            String t = m.content().getString();
            if (!t.isBlank()) printer.accept("[Response] " + t);
        } else if (packet instanceof DisconnectS2CPacket d) {
            String r = d.reason().getString();
            printer.accept("[Disconnect] " + (r == null || r.isBlank() ? "(no reason)" : r));
        }
    }

    private Map<Item, Integer> snapshot() {
        Map<Item, Integer> m = new HashMap<>();
        if (mc.player == null) return m;
        ScreenHandler h = mc.player.currentScreenHandler;
        if (h == null) return m;
        for (int i = 0; i < h.slots.size(); i++) {
            ItemStack s = h.slots.get(i).getStack();
            if (!s.isEmpty()) m.put(s.getItem(), m.getOrDefault(s.getItem(), 0) + s.getCount());
        }
        return m;
    }

    private void diffSnapshot() {
        if (preSnapshot == null) return;
        Map<Item, Integer> post = snapshot();
        for (Map.Entry<Item, Integer> e : post.entrySet()) {
            int before = preSnapshot.getOrDefault(e.getKey(), 0);
            if (e.getValue() > before) dupEvents++;
        }
        for (Map.Entry<Item, Integer> e : preSnapshot.entrySet()) {
            int after = post.getOrDefault(e.getKey(), 0);
            if (after < e.getValue()) lossEvents++;
        }
        preSnapshot = null;
    }

    public void report(Consumer<String> printer) {
        if (!observe.get()) return;
        for (String l : reportLines()) printer.accept(l);
    }

    public List<String> reportLines() {
        List<String> out = new ArrayList<>();
        if (!observe.get()) return out;
        if (kicked) {
            out.add("  Outcome: REJECTED via disconnect" + (kickReason != null && !kickReason.isBlank()
                ? " — \"" + kickReason + "\"" : ""));
        }
        out.add(String.format("  Echo: %d confirmed, %d resynced, %d silent (of %d fires)",
            confirms, resyncs, silent, fires));
        if (dupEvents > 0)
            out.add("  Item delta: DUPLICATION observed on " + dupEvents + " item type(s) — INVESTIGATE");
        else if (lossEvents > 0)
            out.add("  Item delta: items LOST on " + lossEvents + " type(s) (rejected mid-flight)");
        else
            out.add("  Item delta: conserved (no dupe/loss seen client-side)");
        out.add("  Server messages during run: " + messages
            + (messages >= 2 ? " — check for a double balance change" : ""));
        for (String m : recentMessages) out.add("    > " + m);
        if (sawTps) out.add(String.format("  TPS: min %.1f%s", minTps, minTps >= 19.0 ? " (held)" : " — degraded"));
        return out;
    }
}
