package com.example.addon.packet;

import com.example.addon.modules.testing.PacketInspector;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detail / editor for one captured packet.
 *
 * Shows every field. For a C2S record packet, each flat field gets an editable box;
 * "Replay once" / "Replay N" reconstruct the packet (canonical constructor, edited
 * fields swapped, everything else preserved) and send it to the server — the core
 * "change one field, replay, see if the server validates it" workflow. S2C packets
 * are view-only (you cannot send a server->client packet to the server).
 */
public class PacketDetailScreen extends WindowScreen {
    private final long id;
    private final Map<String, WTextBox> editBoxes = new LinkedHashMap<>();
    private WTextBox nBox, delayBox, hexBox;
    private String status = "";

    public PacketDetailScreen(long id) {
        super(GuiThemes.get(), "Packet");
        this.id = id;
    }

    @Override
    public void initWidgets() {
        PacketLog.Entry entry = PacketLog.byId(id);
        if (entry == null) {
            add(theme.label("Packet no longer in buffer (it scrolled out)."));
            back();
            return;
        }
        Packet<?> packet = entry.packet();
        boolean outbound = entry.outbound();   // ground truth: was it sent by us (replayable)?
        editBoxes.clear();

        add(theme.label((entry.outbound() ? "C→S  " : "S→C  ") + PacketDump.displayName(packet, entry.outbound())));

        // all fields (read-only view)
        WTable table = add(theme.table()).expandX().widget();
        for (PacketDump.FieldView f : PacketDump.fields(packet)) {
            table.add(theme.label(f.name()));
            table.add(theme.label(f.value()));
            table.row();
        }

        // copy + back
        WHorizontalList top = add(theme.horizontalList()).widget();
        WButton copy = top.add(theme.button("Copy fields to clipboard")).widget();
        copy.action = () -> {
            StringBuilder sb = new StringBuilder(PacketDump.typeName(packet)).append('\n');
            for (PacketDump.FieldView f : PacketDump.fields(packet)) sb.append(f.name()).append(" = ").append(f.value()).append('\n');
            MinecraftClient.getInstance().keyboard.setClipboard(sb.toString());
            status = "Copied to clipboard."; reload();
        };
        boolean isBlocked = PacketBlock.isBlocked(packet.getClass().getName());
        WButton block = top.add(theme.button(isBlocked ? "Unblock this type" : "Block this type")).widget();
        block.action = () -> {
            if (PacketBlock.isBlocked(packet.getClass().getName())) PacketBlock.unblock(packet.getClass().getName());
            else PacketBlock.block(packet, PacketDump.displayName(packet, entry.outbound()));
            reload();
        };
        WButton back = top.add(theme.button("Back")).widget();
        back.action = this::back;

        // editor / replay — only for outbound (C2S) packets we can send back
        if (outbound) {
            add(theme.horizontalSeparator("Edit & replay"));
            var editable = PacketDump.editable(packet);
            if (editable.isEmpty()) {
                add(theme.label("No flat fields to edit — replay verbatim, or edit raw bytes below."));
            } else {
                WTable et = add(theme.table()).expandX().widget();
                for (PacketDump.Editable ed : editable) {
                    et.add(theme.label(ed.display() + " (" + ed.type().getSimpleName() + ")"));
                    WTextBox box = et.add(theme.textBox(ed.current())).minWidth(160).widget();
                    editBoxes.put(ed.name(), box);   // key by REAL name for reconstruct
                    et.row();
                }
            }
            WHorizontalList rep = add(theme.horizontalList()).widget();
            WButton once = rep.add(theme.button("Replay once")).widget();
            once.action = () -> doReplay(packet, 1);
            rep.add(theme.label("N:"));
            nBox = rep.add(theme.textBox("1")).minWidth(50).widget();
            rep.add(theme.label("delay-ticks:"));
            delayBox = rep.add(theme.textBox("1")).minWidth(50).widget();
            WButton repN = rep.add(theme.button("Replay N")).widget();
            repN.action = () -> doReplay(packet, parse(nBox, 1));

            // ---- raw byte / hex editor (Body Hex + UTF-8) ----
            byte[] wire = PacketCodecIO.encode(packet);
            if (wire != null) {
                add(theme.horizontalSeparator("Raw wire bytes (" + wire.length + " B) — edit & send"));
                add(theme.label("UTF-8: " + utf8Preview(wire)));
                hexBox = add(theme.textBox(toHex(wire))).minWidth(360).widget();
                WHorizontalList hr = add(theme.horizontalList()).widget();
                WButton sendHex = hr.add(theme.button("Send edited bytes")).widget();
                sendHex.action = this::sendHex;
                hr.add(theme.label("(decoded back through the C2S codec, then sent)"));
            } else {
                add(theme.label("Raw wire bytes unavailable (packet not encodable by the vanilla C2S codec)."));
            }
        } else {
            add(theme.label("S2C (inbound) packet — view only (cannot be sent to the server)."));
        }

        if (!status.isBlank()) add(theme.label(status));
    }

    private void doReplay(Packet<?> original, int times) {
        PacketInspector mod = Modules.get().get(PacketInspector.class);
        if (mod == null) { status = "packet-inspector module missing."; reload(); return; }
        Packet<?> out = original;
        if (!editBoxes.isEmpty()) {
            Map<String, String> edits = new HashMap<>();
            editBoxes.forEach((k, v) -> edits.put(k, v.get()));
            out = PacketDump.reconstruct(original, edits);
            if (out == null) { status = "Reconstruct failed — check field types/values."; reload(); return; }
        }
        if (times <= 1) mod.sendNow(out);
        else mod.replay(out, times, parse(delayBox, 1));
        status = "Replayed " + PacketDump.typeName(out) + (times > 1 ? " x" + times : "") + ".";
        reload();
    }

    private void sendHex() {
        PacketInspector mod = Modules.get().get(PacketInspector.class);
        if (mod == null || hexBox == null) { status = "packet-inspector module missing."; reload(); return; }
        byte[] bytes = fromHex(hexBox.get());
        if (bytes == null) { status = "Invalid hex (use pairs like 0A FF, spaces optional)."; reload(); return; }
        Packet<?> p = PacketCodecIO.decode(bytes);
        if (p == null) { status = "Bytes didn't decode to a valid C2S packet."; reload(); return; }
        mod.sendNow(p);
        status = "Sent " + bytes.length + " edited byte(s) as " + PacketDump.typeName(p) + ".";
        reload();
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) { sb.append(Character.forDigit((x >> 4) & 0xF, 16)); sb.append(Character.forDigit(x & 0xF, 16)); sb.append(' '); }
        return sb.toString().trim();
    }

    private static byte[] fromHex(String s) {
        if (s == null) return null;
        String clean = s.replaceAll("[^0-9a-fA-F]", "");
        if (clean.length() % 2 != 0) return null;
        byte[] out = new byte[clean.length() / 2];
        try {
            for (int i = 0; i < out.length; i++)
                out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException e) { return null; }
        return out;
    }

    private static String utf8Preview(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(b.length, 96);
        for (int i = 0; i < n; i++) { char c = (char) (b[i] & 0xFF); sb.append(c >= 0x20 && c < 0x7F ? c : '.'); }
        if (b.length > n) sb.append("…");
        return sb.toString();
    }

    private static int parse(WTextBox box, int def) {
        if (box == null) return def;
        try { return Integer.parseInt(box.get().trim()); } catch (NumberFormatException e) { return def; }
    }

    private void back() { MinecraftClient.getInstance().setScreen(new PacketInspectorScreen()); }
}
