package com.example.addon.modules.crash;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * A client-encodable custom plugin-channel payload with an arbitrary raw body.
 *
 * Used by channel-flood's CUSTOM_PAYLOAD mode (review criterion 2:
 * "valid header, malformed body"). The outbound play C2S encoder is keyed to
 * registered payload types, so to send a custom-channel packet at all we must
 * register an encoder for it — done once at addon init via
 * PayloadTypeRegistry.playC2S(). Once registered, we can ship a valid channel
 * header (Id) with a body of any size/content, which the SERVER must then decode
 * and dispatch safely.
 *
 * The codec writes the raw bytes verbatim (no length prefix it controls), so the
 * body can be oversized or garbage relative to whatever the receiving channel
 * handler expects — exactly the "passes the channel demux, breaks the handler"
 * surface.
 */
public record MalformedPayload(byte[] body) implements CustomPayload {
    public static final CustomPayload.Id<MalformedPayload> ID =
        new CustomPayload.Id<>(Identifier.of("acaudit", "fuzz"));

    public static final PacketCodec<RegistryByteBuf, MalformedPayload> CODEC = PacketCodec.of(
        (value, buf) -> buf.writeBytes(value.body),
        buf -> {
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            return new MalformedPayload(b);
        }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
