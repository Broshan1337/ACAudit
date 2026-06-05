package com.example.addon.packet;

import net.minecraft.network.packet.Packet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reflective read/edit of Minecraft packets for the inspector.
 *
 * READ: extracts (name, value) pairs from any packet — record components for the
 * modern record packets, declared fields otherwise. Values rendered as readable
 * strings (nested types via toString, truncated).
 *
 * EDIT: modern packets are immutable records, so editing means RECONSTRUCTING the
 * packet via its canonical constructor with one or more components swapped. Flat
 * components (int/long/short/byte/double/float/boolean/char/String/enum) are
 * editable; non-editable components (ItemStack, maps, Text, ...) are passed through
 * unchanged from the original, so you can capture a ClickSlotC2SPacket, change only
 * the slot, and replay it with every other field intact.
 */
public final class PacketDump {
    private PacketDump() {}

    public record FieldView(String name, String value) {}
    /** {@code name} is the real (runtime) component name used to reconstruct; {@code display} is readable. */
    public record Editable(String name, String display, Class<?> type, String current) {}

    /** Raw class name (intermediary at production runtime, e.g. "class_2829"). */
    public static String typeName(Packet<?> p) { return p == null ? "null" : p.getClass().getSimpleName(); }

    /**
     * Human-readable name that survives obfuscation. Modern packets carry a
     * {@code PacketType} whose {@code id()} is a real Identifier baked in as a string
     * (e.g. minecraft:custom_payload), so we get "ServerboundCustomPayload" instead of
     * the runtime "class_2829". Custom-payload packets also show their channel. Falls
     * back to the class name if no packet type is available.
     */
    public static String displayName(Packet<?> p, boolean outbound) {
        if (p == null) return "null";
        try {
            var pt = p.getPacketType();
            if (pt != null && pt.id() != null) {
                String name = (outbound ? "Serverbound" : "Clientbound") + camel(pt.id().getPath());
                String ch = channelOf(p);
                return ch != null ? name + " [" + ch + "]" : name;
            }
        } catch (Throwable ignored) {}
        return p.getClass().getSimpleName();
    }

    private static String channelOf(Packet<?> p) {
        try {
            if (p instanceof net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket c)
                return c.payload().getId().id().toString();
            if (p instanceof net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket c)
                return c.payload().getId().id().toString();
        } catch (Throwable ignored) {}
        return null;
    }

    private static String camel(String path) {
        StringBuilder b = new StringBuilder();
        for (String part : path.split("_")) if (!part.isEmpty())
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return b.toString();
    }

    /** True if this is a client->server packet (replayable to the server). */
    public static boolean isC2S(Packet<?> p) {
        return p != null && p.getClass().getName().contains(".c2s.");
    }

    /** All fields as readable (name,value) pairs. */
    public static List<FieldView> fields(Packet<?> p) {
        List<FieldView> out = new ArrayList<>();
        if (p == null) return out;
        Class<?> c = p.getClass();
        if (c.isRecord()) {
            for (RecordComponent rc : c.getRecordComponents()) {
                Object v;
                try { v = rc.getAccessor().invoke(p); } catch (Throwable t) { v = "<err>"; }
                out.add(new FieldView(PacketNames.field(rc.getName()), render(v)));
            }
        } else {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Object v;
                    try { f.setAccessible(true); v = f.get(p); } catch (Throwable t) { v = "<err>"; }
                    out.add(new FieldView(PacketNames.field(f.getName()), render(v)));
                }
            }
        }
        return out;
    }

    /** True if this packet has at least one flat, editable field (record OR plain class). */
    public static boolean isEditable(Packet<?> p) {
        return p != null && !editable(p).isEmpty();
    }

    /**
     * Editable (flat) fields. For records: the record components. For plain classes
     * (e.g. PlayerMoveC2SPacket): the non-static flat instance fields up the hierarchy.
     */
    public static List<Editable> editable(Packet<?> p) {
        List<Editable> out = new ArrayList<>();
        if (p == null) return out;
        Class<?> c = p.getClass();
        if (c.isRecord()) {
            for (RecordComponent rc : c.getRecordComponents()) {
                if (!isFlat(rc.getType())) continue;
                Object v;
                try { v = rc.getAccessor().invoke(p); } catch (Throwable t) { v = null; }
                out.add(new Editable(rc.getName(), PacketNames.field(rc.getName()), rc.getType(), str(v)));
            }
        } else {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !isFlat(f.getType())) continue;
                    Object v;
                    try { f.setAccessible(true); v = f.get(p); } catch (Throwable t) { v = null; }
                    out.add(new Editable(f.getName(), PacketNames.field(f.getName()), f.getType(), str(v)));
                }
            }
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v instanceof Enum<?> e ? e.name() : v);
    }

    /**
     * Rebuild a packet with field edits applied; untouched fields keep their values.
     * Records use the canonical constructor. Plain classes are copied via a codec
     * round-trip (no source-instance mutation, no Unsafe) and the edited fields set by
     * reflection (non-static, non-record final fields are settable after setAccessible).
     * Returns null on failure.
     */
    public static Packet<?> reconstruct(Packet<?> p, Map<String, String> edits) {
        if (p == null) return null;
        try {
            if (p.getClass().isRecord()) {
                RecordComponent[] comps = p.getClass().getRecordComponents();
                Class<?>[] types = new Class<?>[comps.length];
                Object[] args = new Object[comps.length];
                for (int i = 0; i < comps.length; i++) {
                    types[i] = comps[i].getType();
                    Object current = comps[i].getAccessor().invoke(p);
                    String edit = edits.get(comps[i].getName());
                    args[i] = (edit != null) ? parse(edit, comps[i].getType()) : current;
                }
                Constructor<?> ctor = p.getClass().getDeclaredConstructor(types);
                ctor.setAccessible(true);
                return (Packet<?>) ctor.newInstance(args);
            }
            // plain class: fresh copy via codec round-trip, then set edited fields
            Packet<?> target = PacketCodecIO.decode(PacketCodecIO.encode(p));
            if (target == null) target = p;   // best-effort fallback: edit the original in place
            for (Map.Entry<String, String> e : edits.entrySet()) {
                Field f = findField(target.getClass(), e.getKey());
                if (f == null) continue;
                f.setAccessible(true);
                f.set(target, parse(e.getValue(), f.getType()));
            }
            return target;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        return null;
    }

    /**
     * Build a record packet purely from a name->string field map (no source instance).
     * Used by the reflective fallback when loading a saved sequence with no wire bytes.
     * Only works if EVERY component is flat (prim/boxed/String/enum); else returns null.
     */
    public static Packet<?> fromFields(Class<?> cls, Map<String, String> fields) {
        if (cls == null || !cls.isRecord()) return null;
        try {
            RecordComponent[] comps = cls.getRecordComponents();
            Class<?>[] types = new Class<?>[comps.length];
            Object[] args = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                types[i] = comps[i].getType();
                if (!isFlat(comps[i].getType())) return null;
                String v = fields.get(comps[i].getName());
                if (v == null) return null;
                args[i] = parse(v, comps[i].getType());
            }
            Constructor<?> ctor = cls.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return (Packet<?>) ctor.newInstance(args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isFlat(Class<?> t) {
        return t == int.class || t == Integer.class || t == long.class || t == Long.class
            || t == short.class || t == Short.class || t == byte.class || t == Byte.class
            || t == double.class || t == Double.class || t == float.class || t == Float.class
            || t == boolean.class || t == Boolean.class || t == char.class || t == Character.class
            || t == String.class || t.isEnum();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parse(String s, Class<?> t) {
        if (t == String.class) return s;
        if (t == int.class || t == Integer.class) return Integer.parseInt(s.trim());
        if (t == long.class || t == Long.class) return Long.parseLong(s.trim());
        if (t == short.class || t == Short.class) return Short.parseShort(s.trim());
        if (t == byte.class || t == Byte.class) return Byte.parseByte(s.trim());
        if (t == double.class || t == Double.class) return Double.parseDouble(s.trim());
        if (t == float.class || t == Float.class) return Float.parseFloat(s.trim());
        if (t == boolean.class || t == Boolean.class) return Boolean.parseBoolean(s.trim());
        if (t == char.class || t == Character.class) return s.isEmpty() ? ' ' : s.charAt(0);
        if (t.isEnum()) return Enum.valueOf((Class<? extends Enum>) t, s.trim());
        throw new IllegalArgumentException("uneditable type " + t);
    }

    private static String render(Object v) {
        if (v == null) return "null";
        String s;
        if (v instanceof Enum<?> e) s = e.name();
        else s = String.valueOf(v);
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }
}
