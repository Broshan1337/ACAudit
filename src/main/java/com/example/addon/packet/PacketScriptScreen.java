package com.example.addon.packet;

import com.example.addon.AddonTemplate;
import io.netty.buffer.Unpooled;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Packet Script — a write-ops DSL that builds a custom-payload body op-by-op and sends
 * it on a channel (the friend's "Packet Script" tab). Each op is "name arg", separated
 * by ';' (or newlines). Ops run top to bottom into a buffer, which is shipped as a C2S
 * custom payload on the given channel. Own server only.
 *
 * Ops: writeByte/Short/Int/Long/Float/Double <num> · writeVarInt/VarLong <num> ·
 * writeBoolean <true|false> · writeString <text> (varint-len + UTF-8) ·
 * writeStringBytes <text> (raw UTF-8, no length) · writeBytes <hex...>
 */
public class PacketScriptScreen extends WindowScreen {
    private WTextBox channelBox, scriptBox;
    private String status = "";

    public PacketScriptScreen() { super(GuiThemes.get(), "Packet Script (custom payload)"); }

    @Override
    public void initWidgets() {
        add(theme.label("Build a custom-payload body with write-ops, then send it on a channel. Ops separated by ';'."));
        add(theme.label("Ops: writeVarInt n · writeInt n · writeString s · writeStringBytes s · writeBytes 0a ff · writeBoolean true · writeByte/Short/Long/Float/Double n"));

        WHorizontalList ch = add(theme.horizontalList()).widget();
        ch.add(theme.label("Channel:"));
        channelBox = ch.add(theme.textBox("minecraft:register")).minWidth(220).widget();

        add(theme.label("Script:"));
        scriptBox = add(theme.textBox("writeStringBytes fabric:registry_sync")).minWidth(420).widget();

        WHorizontalList row = add(theme.horizontalList()).widget();
        WButton build = row.add(theme.button("Build (preview)")).widget();
        build.action = () -> {
            byte[] b = tryBuild();
            status = b == null ? status : b.length + " byte(s): " + hexPreview(b);
            reload();
        };
        WButton send = row.add(theme.button("Send")).widget();
        send.action = this::send;
        WButton back = row.add(theme.button("Back")).widget();
        back.action = () -> MinecraftClient.getInstance().setScreen(new PacketInspectorScreen());

        if (!status.isBlank()) add(theme.label(status));
    }

    private byte[] tryBuild() {
        try {
            return build(scriptBox.get());
        } catch (Exception e) {
            status = "Build error: " + e.getMessage();
            return null;
        }
    }

    private void send() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) { status = "Join a server first."; reload(); return; }
        byte[] body = tryBuild();
        if (body == null) { reload(); return; }
        try {
            Identifier ch = Identifier.of(channelBox.get().trim());
            AddonTemplate.ensureChannelRegistered(ch);
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(
                new com.example.addon.modules.testing.PluginChannelPayload(ch, body)));
            status = "Sent " + body.length + "B on " + ch + ".";
        } catch (Throwable t) {
            status = "Send failed: " + t.getMessage();
        }
        reload();
    }

    private static byte[] build(String script) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        for (String raw : script.split("[;\n]")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int sp = line.indexOf(' ');
            String op = (sp < 0 ? line : line.substring(0, sp)).toLowerCase();
            String arg = sp < 0 ? "" : line.substring(sp + 1).trim();
            switch (op) {
                case "writebyte" -> buf.writeByte(Integer.parseInt(arg));
                case "writeshort" -> buf.writeShort(Integer.parseInt(arg));
                case "writeint" -> buf.writeInt(Integer.parseInt(arg));
                case "writelong" -> buf.writeLong(Long.parseLong(arg));
                case "writevarint" -> buf.writeVarInt(Integer.parseInt(arg));
                case "writevarlong" -> buf.writeVarLong(Long.parseLong(arg));
                case "writefloat" -> buf.writeFloat(Float.parseFloat(arg));
                case "writedouble" -> buf.writeDouble(Double.parseDouble(arg));
                case "writeboolean" -> buf.writeBoolean(Boolean.parseBoolean(arg));
                case "writestring" -> buf.writeString(arg);
                case "writestringbytes" -> buf.writeBytes(arg.getBytes(StandardCharsets.UTF_8));
                case "writebytes" -> buf.writeBytes(hex(arg));
                default -> throw new IllegalArgumentException("unknown op '" + op + "'");
            }
        }
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    private static byte[] hex(String s) {
        String c = s.replaceAll("[^0-9a-fA-F]", "");
        if (c.length() % 2 != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[c.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(c.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static String hexPreview(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, 40); i++) sb.append(String.format("%02x ", b[i]));
        if (b.length > 40) sb.append("…");
        return sb.toString().trim();
    }
}
