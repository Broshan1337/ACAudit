package com.example.addon.audit;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic, module-agnostic observation of one vector's window.
 *
 * Lets the runner grade ANY vector from the client-visible packet stream without
 * touching the 80 attack modules: it counts setbacks / inventory resyncs / slot
 * updates / server messages, diffs the player's inventory totals before vs after
 * (dupe/loss), runs every server-originated string through {@link AcSignalParser}
 * (chat + action-bar + title + kick reason), tracks min TPS and whether the
 * server HUNG (stopped ticking while connected), and produces a severity-classified
 * {@link Finding}.
 *
 * This is the same honest client-side signal the per-module observers use, lifted
 * to the pipeline so the report carries real verdicts instead of "inspect logs".
 */
public final class VectorObservation {
    public int setbacks, resyncs, slotUpdates, messages;
    public double minTps = 20.0;
    public boolean sawTps;
    public boolean disconnected;
    public String disconnectReason;
    public boolean hang;
    public double recoveryMs = -1;
    private int dupEvents, lossEvents;
    private Map<Item, Integer> pre;
    private final List<AcSignalParser.Signal> signals = new ArrayList<>();
    private final List<String> sampleMessages = new ArrayList<>();

    public void reset() {
        setbacks = resyncs = slotUpdates = messages = 0;
        minTps = 20.0; sawTps = false;
        disconnected = false; disconnectReason = null; hang = false; recoveryMs = -1;
        dupEvents = lossEvents = 0; pre = null;
        signals.clear(); sampleMessages.clear();
    }

    public void snapshotPre(ClientPlayerEntity player) { pre = snapshot(player); }

    public void snapshotPost(ClientPlayerEntity player) {
        if (pre == null) return;
        Map<Item, Integer> post = snapshot(player);
        for (Map.Entry<Item, Integer> e : post.entrySet())
            if (e.getValue() > pre.getOrDefault(e.getKey(), 0)) dupEvents++;
        for (Map.Entry<Item, Integer> e : pre.entrySet())
            if (post.getOrDefault(e.getKey(), 0) < e.getValue()) lossEvents++;
    }

    private static Map<Item, Integer> snapshot(ClientPlayerEntity player) {
        Map<Item, Integer> m = new HashMap<>();
        if (player == null) return m;
        var main = player.getInventory().getMainStacks();
        for (int i = 0; i < main.size(); i++) {
            ItemStack s = main.get(i);
            if (!s.isEmpty()) m.put(s.getItem(), m.getOrDefault(s.getItem(), 0) + s.getCount());
        }
        return m;
    }

    public void sampleTps(double tps) { minTps = Math.min(minTps, tps); sawTps = true; }

    public void onPacket(Object p) {
        if (p instanceof PlayerPositionLookS2CPacket) setbacks++;
        else if (p instanceof InventoryS2CPacket) resyncs++;
        else if (p instanceof ScreenHandlerSlotUpdateS2CPacket) slotUpdates++;
        else if (p instanceof GameMessageS2CPacket m) ingest(m.content().getString(), "chat");
        else if (p instanceof OverlayMessageS2CPacket m) ingest(m.text().getString(), "actionbar");
        else if (p instanceof TitleS2CPacket m) ingest(m.text().getString(), "title");
        else if (p instanceof SubtitleS2CPacket m) ingest(m.text().getString(), "title");
        else if (p instanceof DisconnectS2CPacket d) {
            disconnected = true;
            disconnectReason = d.reason().getString();
            ingest(disconnectReason, "kick");
        }
    }

    private void ingest(String text, String channel) {
        if (text == null || text.isBlank()) return;
        if (!"kick".equals(channel)) {
            messages++;
            if (sampleMessages.size() < 4) sampleMessages.add("[" + channel + "] " + text);
        }
        AcSignalParser.Signal s = AcSignalParser.parse(text, channel);
        if (s.detected()) signals.add(s);
    }

    public boolean acDetected() { return !signals.isEmpty(); }
    public boolean duplicated() { return dupEvents > 0; }

    private String acSummary() {
        if (signals.isEmpty()) return null;
        AcSignalParser.Signal s = signals.get(0);
        return "AC signal: " + s.ac() + " via " + s.channel() + " — \"" + trim(s.raw()) + "\""
            + (signals.size() > 1 ? " (+" + (signals.size() - 1) + " more)" : "");
    }

    private static String trim(String s) { return s.length() > 80 ? s.substring(0, 77) + "..." : s; }

    /**
     * Build the finding for this vector.
     * @param vector   module name
     * @param category CRASH | DUPE | MOVEMENT | ECON | COMBO
     * @param tpsFloor crash PASS threshold
     */
    public Finding classify(String vector, String category, double tpsFloor) {
        List<String> det = new ArrayList<>();
        if (sawTps) det.add(String.format("min TPS %.1f%s", minTps, recoveryMs >= 0
            ? String.format(", recovered in %.1fs", recoveryMs / 1000.0) : ""));
        if (setbacks > 0) det.add(setbacks + " setback(s)");
        if (resyncs > 0) det.add(resyncs + " inventory resync(s)");
        if (slotUpdates > 0) det.add(slotUpdates + " slot update(s)");
        if (messages > 0) det.add(messages + " server message(s)");
        String ac = acSummary();
        if (ac != null) det.add(ac);
        for (String m : sampleMessages) det.add(m);

        // Disconnect / hang dominate everything.
        if (disconnected) {
            det.add(0, "DISCONNECTED" + (disconnectReason != null && !disconnectReason.isBlank()
                ? " — \"" + disconnectReason + "\"" : ""));
            return new Finding(vector, category, Severity.CRITICAL,
                hang ? "CRASH(after-hang)" : "DISCONNECTED", det);
        }
        if (hang) {
            det.add(0, "server stopped ticking while connected (HANG)");
            return new Finding(vector, category, Severity.CRITICAL, "HANG", det);
        }

        switch (category) {
            case "COMBO" -> {
                if (dupEvents > 0) {
                    det.add(0, "inventory grew on " + dupEvents + " item type(s) under combined load");
                    return new Finding(vector, category, Severity.CRITICAL, "DUPLICATED", det);
                }
                if (minTps < 10.0) return new Finding(vector, category, Severity.HIGH, "DEGRADED", det);
                if (minTps < tpsFloor) return new Finding(vector, category, Severity.MEDIUM, "DEGRADED", det);
                if (acDetected()) return new Finding(vector, category, Severity.LOW, "DETECTED", det);
                return new Finding(vector, category, Severity.INFO, "HELD", det);
            }
            case "DUPE" -> {
                if (dupEvents > 0) {
                    det.add(0, "inventory grew on " + dupEvents + " item type(s)");
                    return new Finding(vector, category, Severity.CRITICAL, "DUPLICATED", det);
                }
                if (lossEvents > 0) {
                    det.add("items lost on " + lossEvents + " type(s) (rejected mid-flight)");
                    return new Finding(vector, category, Severity.MEDIUM, "LOST", det);
                }
                // silent: nothing came back at all
                if (resyncs == 0 && slotUpdates == 0 && messages == 0)
                    return new Finding(vector, category, Severity.HIGH, "SILENT-NO-ECHO", det);
                return new Finding(vector, category, Severity.INFO, "RAN", det);
            }
            case "MOVEMENT" -> {
                if (acDetected())
                    return new Finding(vector, category, Severity.INFO, "DETECTED", det);
                if (setbacks > 0)
                    return new Finding(vector, category, Severity.LOW, "CORRECTED", det);
                return new Finding(vector, category, Severity.MEDIUM, "UNDETECTED", det);
            }
            case "ECON" -> {
                if (acDetected())
                    return new Finding(vector, category, Severity.LOW, "REJECTED", det);
                return new Finding(vector, category, Severity.INFO, "RAN", det);
            }
            default -> { // CRASH and combos graded on TPS
                if (minTps < 10.0) return new Finding(vector, category, Severity.HIGH, "DEGRADED", det);
                if (minTps < tpsFloor) return new Finding(vector, category, Severity.MEDIUM, "DEGRADED", det);
                return new Finding(vector, category, Severity.INFO, "PASS", det);
            }
        }
    }
}
