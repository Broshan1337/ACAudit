package com.example.addon.modules.injection;

import com.example.addon.audit.DetectedPlugins;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the plugin-specific payload library.
 *
 * Data-driven so the community can maintain it without touching code: the bundled
 * {@code assets/acaudit/plugin_payloads.json} is the default, and an optional
 * {@code config/acaudit/plugin_payloads.json} overrides/extends it (matched by
 * plugin id). See CONTRIBUTING.md for the schema.
 *
 * Each plugin records its STORAGE model so the fuzzer only sends payload types that
 * can possibly work — FLATFILE plugins (e.g. EssentialsX userdata) have no SQL
 * surface, so SQLI entries there are intentionally absent.
 */
public final class PluginLibrary {
    private PluginLibrary() {}

    public static final class PluginPayload {
        public String type = "SQLI";   // SQLI | FORMAT | LENGTH | SPECIAL
        public String text = "";
        public boolean destructive = false;
        public int delayMs = 0;
        public String note = "";
        public String vulnerableSignal = "";
    }
    public static final class PluginCommand {
        public String template = "";   // contains {x}
        public String arg = "";
        public String surface = "";    // SQL | DISPLAYED | PERMISSION
        public String notes = "";
        public List<PluginPayload> payloads = new ArrayList<>();
    }
    public static final class PluginEntry {
        public String id = "";
        public List<String> namespaces = new ArrayList<>();
        public String storage = "UNKNOWN";   // FLATFILE | H2 | MYSQL | SQLITE | MIXED
        public String disclosureUrl = "";
        public String fixExample = "";
        public List<PluginCommand> commands = new ArrayList<>();
    }
    private static final class Root { List<PluginEntry> plugins; }

    private static final Gson GSON = new Gson();
    private static List<PluginEntry> cache;

    /** Load (bundled + config override), cached. */
    public static synchronized List<PluginEntry> all() {
        if (cache == null) cache = load();
        return cache;
    }

    public static synchronized void reload() { cache = null; }

    private static List<PluginEntry> load() {
        Map<String, PluginEntry> byId = new LinkedHashMap<>();
        // bundled resource
        try (InputStream in = PluginLibrary.class.getResourceAsStream("/assets/acaudit/plugin_payloads.json")) {
            if (in != null) {
                Root r = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Root.class);
                if (r != null && r.plugins != null) for (PluginEntry e : r.plugins) byId.put(e.id, e);
            }
        } catch (Exception ignored) {}
        // optional user override
        try {
            File f = new File(MinecraftClient.getInstance().runDirectory, "config/acaudit/plugin_payloads.json");
            if (f.exists()) {
                try (FileReader fr = new FileReader(f)) {
                    Root r = GSON.fromJson(JsonParser.parseReader(fr), Root.class);
                    if (r != null && r.plugins != null) for (PluginEntry e : r.plugins) byId.put(e.id, e); // override by id
                }
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(byId.values());
    }

    public static PluginEntry byId(String id) {
        for (PluginEntry e : all()) if (e.id.equalsIgnoreCase(id)) return e;
        return null;
    }

    /** First entry whose namespaces contain the given namespace. */
    public static PluginEntry byNamespace(String ns) {
        if (ns == null) return null;
        String n = ns.toLowerCase().trim();
        for (PluginEntry e : all())
            for (String x : e.namespaces) if (x.equalsIgnoreCase(n)) return e;
        return null;
    }

    /** Library entries that match a plugin namespace command-fingerprint detected. */
    public static List<PluginEntry> suggestedFromDetected() {
        List<PluginEntry> out = new ArrayList<>();
        for (String ns : DetectedPlugins.snapshot().keySet()) {
            PluginEntry e = byNamespace(ns);
            if (e != null && !out.contains(e)) out.add(e);
        }
        return out;
    }

    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (PluginEntry e : all()) out.add(e.id);
        return out;
    }
}
