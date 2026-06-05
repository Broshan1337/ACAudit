package com.example.addon.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.listener.ServerPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.registry.DynamicRegistryManager;
import java.util.Base64;

/**
 * Encodes/decodes C2S play packets to/from wire bytes, so a captured sequence can be
 * saved to disk and faithfully replayed in a later session.
 *
 * Uses the same codec the client uses on the wire: bind the play C2S state factory with
 * the connection's registry manager, then {@code codec().encode/decode}. This round-trips
 * ANY C2S packet (unlike reflective field JSON, which only handles flat records). Returns
 * null on any failure so callers can fall back to the reflective representation.
 */
public final class PacketCodecIO {
    private PacketCodecIO() {}

    private static PacketCodec<ByteBuf, Packet<? super ServerPlayPacketListener>> codec() {
        var nh = MinecraftClient.getInstance().getNetworkHandler();
        if (nh == null) return null;
        DynamicRegistryManager drm = nh.getRegistryManager();
        return PlayStateFactories.C2S.bind(RegistryByteBuf.makeFactory(drm), () -> false).codec();
    }

    /** Encode an outbound (C2S) packet to wire bytes (incl. its packet-id), or null. */
    @SuppressWarnings("unchecked")
    public static byte[] encode(Packet<?> packet) {
        if (packet == null) return null;   // the C2S codec rejects non-C2S -> null
        try {
            var c = codec();
            if (c == null) return null;
            ByteBuf buf = Unpooled.buffer();
            c.encode(buf, (Packet<? super ServerPlayPacketListener>) packet);
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            buf.release();
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Decode wire bytes back into a C2S packet, or null on failure. */
    public static Packet<?> decode(byte[] bytes) {
        if (bytes == null) return null;
        try {
            var c = codec();
            if (c == null) return null;
            return c.decode(Unpooled.wrappedBuffer(bytes));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Encode an outbound (C2S) packet to base64 wire bytes, or null if it can't be encoded. */
    public static String encodeBase64(Packet<?> packet) {
        byte[] b = encode(packet);
        return b == null ? null : Base64.getEncoder().encodeToString(b);
    }

    /** Decode base64 wire bytes back into a C2S packet, or null on failure. */
    public static Packet<?> decodeBase64(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        try {
            return decode(Base64.getDecoder().decode(base64));
        } catch (Throwable t) {
            return null;
        }
    }
}
