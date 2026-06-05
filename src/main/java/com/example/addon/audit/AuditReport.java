package com.example.addon.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects {@link Finding}s for one audit run and renders them three ways:
 *
 *   - <b>text</b>     — human-readable, what shows in chat / a quick read.
 *   - <b>markdown</b> — a table you can paste straight into a GitHub issue or a
 *                       Discord report to a plugin / anti-cheat developer.
 *   - <b>json</b>     — machine-readable, and the input for REGRESSION diffing
 *                       (run, save baseline, run again after an update, diff).
 *
 * Every report embeds the PLATFORM context (server brand + classification) so a
 * developer reading it immediately knows what stack produced the results.
 *
 * Files land in {@code <mc-run-dir>/config/acaudit/reports/} as
 * {@code <prefix>_YYYY-MM-DD_HH-mm-ss.{txt,md,json}}.
 */
public final class AuditReport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String mode;
    private final String timestamp;
    private String serverAddress = "unknown";
    private String platformBrand = "(not captured — enable platform-probe before joining)";
    private String platform = "unknown";
    private final List<Finding> findings = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    public AuditReport(String mode) {
        this.mode = mode;
        this.timestamp = LocalDateTime.now().format(TS);
    }

    public AuditReport setServer(String addr) { if (addr != null) this.serverAddress = addr; return this; }
    public AuditReport setPlatform(String brand, String classification) {
        if (brand != null) this.platformBrand = brand;
        if (classification != null) this.platform = classification;
        return this;
    }
    public void add(Finding f) { findings.add(f); }
    public AuditReport addNote(String n) { if (n != null) notes.add(n); return this; }
    public List<Finding> findings() { return findings; }
    public String timestamp() { return timestamp; }

    public Map<Severity, Integer> counts() {
        Map<Severity, Integer> m = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) m.put(s, 0);
        for (Finding f : findings) m.put(f.severity(), m.get(f.severity()) + 1);
        return m;
    }

    /** Summary line plus a one-line callout of any CRITICAL/HIGH vectors, for chat. */
    public List<String> summaryLineDetail() {
        List<String> out = new ArrayList<>();
        out.add("Summary: " + summaryLine());
        List<String> top = new ArrayList<>();
        for (Finding f : sorted())
            if (f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH)
                top.add(f.severity().tag() + " " + f.vector() + " (" + f.verdict() + ")");
        if (!top.isEmpty()) out.add("Attention: " + String.join(", ", top));
        return out;
    }

    public String summaryLine() {
        Map<Severity, Integer> c = counts();
        return String.format("CRITICAL %d | HIGH %d | MEDIUM %d | LOW %d | INFO %d",
            c.get(Severity.CRITICAL), c.get(Severity.HIGH), c.get(Severity.MEDIUM),
            c.get(Severity.LOW), c.get(Severity.INFO));
    }

    private List<Finding> sorted() {
        List<Finding> s = new ArrayList<>(findings);
        s.sort(Comparator.comparingInt((Finding f) -> f.severity().rank())
            .thenComparing(Finding::category).thenComparing(Finding::vector));
        return s;
    }

    // ---- text ----
    public List<String> textLines() {
        List<String> out = new ArrayList<>();
        out.add("ACAudit Report — " + timestamp);
        out.add("Mode: " + mode + "   Server: " + serverAddress);
        out.add("Platform: " + platform);
        out.add("Brand:    " + platformBrand);
        out.add("Summary:  " + summaryLine());
        out.add("-".repeat(72));
        for (Finding f : sorted()) {
            out.add(f.textLine());
            for (String d : f.details()) out.add("        - " + d);
        }
        out.add("-".repeat(72));
        if (!notes.isEmpty()) {
            out.add("Notes:");
            for (String n : notes) out.add("  " + n);
            out.add("-".repeat(72));
        }
        out.add("Severity: CRITICAL=crash/confirmed-dupe  HIGH=AC/econ bypass  MEDIUM=degraded/ambiguous  LOW/INFO=context");
        out.add("AC-detection lines are heuristic client-side signals — correlate with server/AC logs.");
        return out;
    }

    // ---- markdown ----
    public List<String> markdownLines() {
        List<String> out = new ArrayList<>();
        out.add("## ACAudit Report — " + timestamp);
        out.add("");
        out.add("- **Mode:** " + mode);
        out.add("- **Server:** " + serverAddress);
        out.add("- **Platform:** " + platform);
        out.add("- **Brand:** `" + platformBrand + "`");
        out.add("- **Summary:** " + summaryLine());
        out.add("");
        out.add("| Severity | Vector | Category | Verdict | Detail |");
        out.add("|---|---|---|---|---|");
        for (Finding f : sorted()) out.add(f.markdownRow());
        out.add("");
        if (!notes.isEmpty()) {
            out.add("**Notes**");
            out.add("");
            for (String n : notes) out.add("- " + n);
            out.add("");
        }
        out.add("> Severity: CRITICAL = crash / confirmed dupe · HIGH = AC or economy bypass · MEDIUM = degraded or ambiguous · LOW/INFO = context.");
        out.add("> AC-detection rows are heuristic client-side signals — correlate with server/AC logs.");
        return out;
    }

    // ---- json ----
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("tool", "ACAudit");
        root.addProperty("timestamp", timestamp);
        root.addProperty("mode", mode);
        root.addProperty("server", serverAddress);
        root.addProperty("platformBrand", platformBrand);
        root.addProperty("platform", platform);
        JsonObject summary = new JsonObject();
        counts().forEach((k, v) -> summary.addProperty(k.name(), v));
        root.add("summary", summary);
        JsonArray arr = new JsonArray();
        for (Finding f : sorted()) {
            JsonObject o = new JsonObject();
            o.addProperty("vector", f.vector());
            o.addProperty("category", f.category());
            o.addProperty("severity", f.severity().name());
            o.addProperty("verdict", f.verdict());
            JsonArray d = new JsonArray();
            for (String s : f.details()) d.add(s);
            o.add("details", d);
            arr.add(o);
        }
        root.add("findings", arr);
        JsonArray notesArr = new JsonArray();
        for (String n : notes) notesArr.add(n);
        root.add("notes", notesArr);
        return root;
    }

    /**
     * Write all three formats. Returns the JSON {@link File} (the regression input),
     * or null on failure.
     */
    public File save(File runDir, String prefix) {
        try {
            File dir = new File(runDir, "config/acaudit/reports");
            dir.mkdirs();
            String base = prefix + "_" + timestamp.replace(" ", "_").replace(":", "-");
            write(new File(dir, base + ".txt"), textLines());
            write(new File(dir, base + ".md"), markdownLines());
            File json = new File(dir, base + ".json");
            try (PrintWriter pw = new PrintWriter(new FileWriter(json))) {
                pw.println(GSON.toJson(toJson()));
            }
            return json;
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(File f, List<String> lines) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
            for (String l : lines) pw.println(l);
        }
    }

    // ---- regression ----

    /** Most recent *.json in the reports dir other than {@code exclude}, or null. */
    public static File latestJson(File runDir, File exclude) {
        File dir = new File(runDir, "config/acaudit/reports");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (exclude != null && f.getName().equals(exclude.getName())) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    /**
     * Diff this run against a previous JSON report. Returns human lines describing
     * what changed per vector: NEW, RESOLVED, REGRESSED (severity worse), IMPROVED
     * (severity better), CHANGED (verdict differs), or "no change".
     */
    public List<String> diffAgainst(File prevJson) {
        List<String> out = new ArrayList<>();
        Map<String, String[]> prev = loadFindings(prevJson);  // key -> {severity, verdict}
        if (prev == null) { out.add("Regression: previous report unreadable — skipped."); return out; }

        Map<String, String[]> cur = new HashMap<>();
        for (Finding f : findings) cur.put(f.key(), new String[]{f.severity().name(), f.verdict()});

        out.add("Regression vs " + prevJson.getName() + ":");
        int changes = 0;
        for (Map.Entry<String, String[]> e : cur.entrySet()) {
            String[] p = prev.get(e.getKey());
            String[] c = e.getValue();
            if (p == null) { out.add("  + NEW       " + e.getKey() + "  (" + c[0] + "/" + c[1] + ")"); changes++; continue; }
            int pr = rankOf(p[0]), cr = rankOf(c[0]);
            boolean verdictChanged = !p[1].equals(c[1]);
            if (cr < pr) { out.add("  ! REGRESSED " + e.getKey() + "  " + p[0] + "->" + c[0] + " (" + c[1] + ")"); changes++; }
            else if (cr > pr) { out.add("  ~ IMPROVED  " + e.getKey() + "  " + p[0] + "->" + c[0] + " (" + c[1] + ")"); changes++; }
            else if (verdictChanged) { out.add("  ~ CHANGED   " + e.getKey() + "  " + p[1] + "->" + c[1]); changes++; }
        }
        for (String k : prev.keySet())
            if (!cur.containsKey(k)) { out.add("  - RESOLVED  " + k + "  (was " + prev.get(k)[0] + ")"); changes++; }
        if (changes == 0) out.add("  no changes — results match the previous run.");
        return out;
    }

    /** key -> {severity, verdict} from a saved report json, or null. */
    public static Map<String, String[]> loadFindings(File json) {
        try (FileReader r = new FileReader(json)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("findings");
            Map<String, String[]> m = new HashMap<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                String key = o.get("category").getAsString() + "/" + o.get("vector").getAsString();
                m.put(key, new String[]{o.get("severity").getAsString(), o.get("verdict").getAsString()});
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static int rankOf(String sev) {
        try { return Severity.valueOf(sev).rank(); } catch (Exception e) { return Severity.INFO.rank(); }
    }
}
