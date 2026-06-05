package com.example.addon.packet;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates obfuscated runtime field names to readable Yarn names.
 *
 * A production client runs in the INTERMEDIARY namespace, so reflected field /
 * record-component names come out as {@code field_12889} / {@code comp_1646}. Yarn
 * names aren't shipped to production and Fabric's resolver only maps the other
 * direction, so we bundle a generated map ({@code assets/acaudit/field_names.json},
 * packet-scoped: intermediary -> named) and look names up here. In a dev (named)
 * runtime the lookup misses and we return the name unchanged, which is already
 * readable — so it's correct in both environments.
 */
public final class PacketNames {
    private PacketNames() {}

    private static Map<String, String> map;

    private static Map<String, String> map() {
        if (map == null) {
            try (var in = PacketNames.class.getResourceAsStream("/assets/acaudit/field_names.json")) {
                map = in == null ? new HashMap<>()
                    : new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() {}.getType());
            } catch (Exception e) {
                map = new HashMap<>();
            }
            if (map == null) map = new HashMap<>();
        }
        return map;
    }

    /** Readable name for a reflected field/component name, or the input if unknown. */
    public static String field(String runtimeName) {
        if (runtimeName == null) return null;
        return map().getOrDefault(runtimeName, runtimeName);
    }
}
