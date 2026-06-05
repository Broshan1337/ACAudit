package com.example.addon.packet;

import com.example.addon.audit.DetectedPlugins;
import com.example.addon.modules.testing.PlatformProbe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cross-session persistence for captured packet sequences.
 *
 * Hybrid format (human-readable + faithful): each packet stores a reflective field
 * dump (readable / lightly editable) AND base64 wire bytes (the source of truth for
 * replay). On load, wire bytes are decoded with the live codec; if they're missing or
 * a different Minecraft version makes them undecodable, it falls back to rebuilding
 * flat-record packets from the fields, and skips ones it can't.
 *
 * Sequences live in {@code config/acaudit/sequences/*.json} so they persist and are
 * easy to share (export/import is just the file). Each carries self-documenting
 * metadata: what it tests, the MC version + platform + detected plugins it was captured
 * on, and the expected vulnerable/patched responses.
 */
public final class SequenceStore {
    private SequenceStore() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final class SavedPacket {
        public String type;                 // fully-qualified class (for reflective fallback)
        public long delayMs;                // from sequence start
        public Map<String, String> fields;  // human-readable
        public String wire;                 // base64 wire bytes (may be null)
    }
    public static final class SavedSequence {
        public String name = "";
        public String description = "";
        public String created = "";
        public String mcVersion = "";
        public String serverContext = "";
        public String platform = "";
        public List<String> detectedPlugins = new ArrayList<>();
        public List<String> tags = new ArrayList<>();
        public String expectedVulnerable = "";
        public String expectedPatched = "";
        public List<SavedPacket> packets = new ArrayList<>();
    }

    public record Timed(Packet<?> packet, long delayMs) {}
    public record LoadResult(List<Timed> items, int wireUsed, int reflectiveUsed, int skipped,
                             boolean versionMismatch, String savedVersion) {}

    private static File dir() {
        File d = new File(MinecraftClient.getInstance().runDirectory, "config/acaudit/sequences");
        d.mkdirs();
        return d;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9-_]", "_");
    }

    /** Build a saved sequence (with current metadata) from recorded outbound entries. */
    public static SavedSequence build(String name, String description, List<String> tags,
                                      String expectedVulnerable, String expectedPatched,
                                      List<PacketLog.Entry> entries) {
        SavedSequence s = new SavedSequence();
        s.name = name;
        s.description = description;
        s.created = LocalDateTime.now().format(TS);
        s.mcVersion = SharedConstants.getGameVersion().name();
        s.tags = tags != null ? tags : new ArrayList<>();
        s.expectedVulnerable = expectedVulnerable == null ? "" : expectedVulnerable;
        s.expectedPatched = expectedPatched == null ? "" : expectedPatched;
        var entry = MinecraftClient.getInstance().getCurrentServerEntry();
        s.serverContext = entry != null ? entry.address : "unknown";
        PlatformProbe pp = Modules.get().get(PlatformProbe.class);
        s.platform = pp != null && pp.getPlatform() != null ? pp.getPlatform() : "unknown";
        s.detectedPlugins = new ArrayList<>(DetectedPlugins.snapshot().keySet());

        long base = entries.isEmpty() ? 0 : entries.get(0).time();
        for (PacketLog.Entry e : entries) {
            if (!e.outbound()) continue;   // only outbound (C2S) packets can be replayed
            SavedPacket sp = new SavedPacket();
            sp.type = e.packet().getClass().getName();
            sp.delayMs = e.time() - base;
            Map<String, String> fm = new java.util.LinkedHashMap<>();
            for (PacketDump.FieldView fv : PacketDump.fields(e.packet())) fm.put(fv.name(), fv.value());
            sp.fields = fm;
            sp.wire = PacketCodecIO.encodeBase64(e.packet());
            s.packets.add(sp);
        }
        return s;
    }

    public static boolean save(SavedSequence s) {
        try (FileWriter w = new FileWriter(new File(dir(), sanitize(s.name) + ".json"))) {
            GSON.toJson(s, w);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<SavedSequence> list() {
        List<SavedSequence> out = new ArrayList<>();
        File[] files = dir().listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            SavedSequence s = read(f);
            if (s != null) out.add(s);
        }
        return out;
    }

    public static SavedSequence load(String name) { return read(new File(dir(), sanitize(name) + ".json")); }

    private static SavedSequence read(File f) {
        try (FileReader r = new FileReader(f)) {
            return GSON.fromJson(r, SavedSequence.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean delete(String name) {
        return new File(dir(), sanitize(name) + ".json").delete();
    }

    /** Import a sequence file from anywhere into the sequences dir. */
    public static SavedSequence importFile(File src) {
        SavedSequence s = read(src);
        if (s != null) save(s);
        return s;
    }

    public static File fileFor(String name) { return new File(dir(), sanitize(name) + ".json"); }

    /** Decode a saved sequence into replayable packets, preferring faithful wire bytes. */
    public static LoadResult toPackets(SavedSequence s) {
        List<Timed> items = new ArrayList<>();
        int wire = 0, refl = 0, skip = 0;
        String current = SharedConstants.getGameVersion().name();
        boolean mismatch = s.mcVersion != null && !s.mcVersion.equals(current);
        for (SavedPacket sp : s.packets) {
            Packet<?> p = null;
            if (sp.wire != null && !sp.wire.isBlank()) {
                p = PacketCodecIO.decodeBase64(sp.wire);
                if (p != null) wire++;
            }
            if (p == null) {
                try {
                    Class<?> cls = Class.forName(sp.type);
                    p = PacketDump.fromFields(cls, sp.fields);
                } catch (Throwable ignored) {}
                if (p != null) refl++;
            }
            if (p == null) { skip++; continue; }
            items.add(new Timed(p, sp.delayMs));
        }
        return new LoadResult(items, wire, refl, skip, mismatch, s.mcVersion);
    }
}
