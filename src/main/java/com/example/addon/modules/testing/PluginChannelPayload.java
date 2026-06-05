package com.example.addon.modules.testing;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * A client-encodable plugin-message payload on an ARBITRARY registered channel.
 *
 * Unlike crash/MalformedPayload (fixed acaudit:fuzz channel), this carries the
 * channel Identifier on the instance so one codec can serve several real plugin
 * channels (bungeecord:main, minecraft:register, minecraft:unregister) that
 * plugin-message-probe needs. The outbound C2S encoder is keyed to registered
 * payload types, so AddonTemplate registers this codec under each channel Id at
 * init; the wire format is the channel Identifier (from getId()) followed by the
 * raw body this codec writes verbatim.
 */
public record PluginChannelPayload(Identifier channel, byte[] body) implements CustomPayload {
    public static PacketCodec<RegistryByteBuf, PluginChannelPayload> codec(Identifier ch) {
        return PacketCodec.of(
            (value, buf) -> buf.writeBytes(value.body),
            buf -> { byte[] b = new byte[buf.readableBytes()]; buf.readBytes(b); return new PluginChannelPayload(ch, b); });
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return new CustomPayload.Id<>(channel); }
}
