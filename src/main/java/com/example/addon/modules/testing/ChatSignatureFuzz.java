package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.KickClassifier;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.message.LastSeenMessageList;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;

import java.time.Instant;
import java.util.BitSet;
import java.util.Random;

/**
 * AUDIT: Chat Signature / Timestamp Fuzz (secure-chat trust)
 *
 * <p>Since 1.19, chat is signed: each {@link ChatMessageC2SPacket} carries a timestamp, a
 * salt, a cryptographic signature and a last-seen-messages acknowledgment. A server with
 * {@code enforce-secure-profile=true} must reject messages whose signature/timestamp don't
 * hold up; a server that trusts client chat blindly (offline mode, misconfigured, or a plugin
 * that reads chat before validation) will process a forged message. This probe sends messages
 * whose signature is garbage, or whose timestamp is far in the past/future, and reports whether
 * the server ENFORCES (rejects/kicks) or ACCEPTS.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code BAD_SIGNATURE} — real text, random 256-byte signature.</li>
 *   <li>{@code FUTURE_TIMESTAMP} / {@code PAST_TIMESTAMP} — timestamp skewed by hours; tests
 *       replay/ordering windows and any plugin that trusts the client clock.</li>
 *   <li>{@code CHAT_THEN_COMMAND} — a chat message and a command in the SAME tick (via the
 *       safe client helpers, correct ack) to test whether processing order is deterministic.</li>
 * </ul>
 *
 * <p><b>Honest caveat:</b> the crafted modes send an EMPTY last-seen acknowledgment (the real
 * client acknowledgment chain isn't reconstructable without a mixin). On a secure-chat server
 * that alone can trigger a "chat validation" disconnect — which is still the correct PASS signal
 * (the server enforced). The vulnerability finding is the opposite: the forged message appears
 * in chat / a plugin acts on it with NO disconnect. Compare against a normal chat working first.
 *
 * <p>Patch signal: enforce secure-profile; validate signature + timestamp window server-side
 * before any plugin sees the message; never let plugins read unsigned/unvalidated chat content.
 * Non-op, client-side. Own server only.
 */
public class ChatSignatureFuzz extends Module {
    public enum Mode { BAD_SIGNATURE, FUTURE_TIMESTAMP, PAST_TIMESTAMP, CHAT_THEN_COMMAND }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which chat trust assumption to probe.")
        .defaultValue(Mode.BAD_SIGNATURE).build());
    private final Setting<String> message = sgGeneral.add(new StringSetting.Builder()
        .name("message").description("Text to send (a marker you can spot in chat).")
        .defaultValue("acaudit-secure-chat-probe").build());
    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("CHAT_THEN_COMMAND: command to send alongside the chat (no leading slash).")
        .defaultValue("list").visible(() -> mode.get() == Mode.CHAT_THEN_COMMAND).build());
    private final Setting<Integer> skewHours = sgGeneral.add(new IntSetting.Builder()
        .name("timestamp-skew-hours").description("How far to skew the timestamp for PAST/FUTURE modes.")
        .defaultValue(6).range(1, 240).sliderRange(1, 48)
        .visible(() -> mode.get() == Mode.FUTURE_TIMESTAMP || mode.get() == Mode.PAST_TIMESTAMP).build());
    private final Setting<Integer> count = sgGeneral.add(new IntSetting.Builder()
        .name("count").description("How many messages to send (one-shot on activate). Keep low — chat has rate limits.")
        .defaultValue(1).range(1, 20).sliderRange(1, 5).build());

    private final Random rng = new Random();
    private boolean disconnected = false;

    public ChatSignatureFuzz() {
        super(AddonTemplate.TESTING_CATEGORY, "chat-signature-fuzz",
            "Sends chat with a garbage signature or skewed timestamp (or chat+command same tick) to test whether the server enforces secure chat or trusts client input.");
    }

    @Override
    public void onActivate() {
        disconnected = false;
        if (mc.player == null) { warning("Not in game."); toggle(); return; }
        for (int i = 0; i < count.get(); i++) sendOne(i);
        info("Sent %d message(s) in mode %s. Watch: a validation kick = ENFORCED (PASS); the message appearing with no kick = TRUSTED (finding).",
            count.get(), mode.get());
        info("Disable when done (one-shot).");
    }

    private void sendOne(int i) {
        var nh = mc.player.networkHandler;
        switch (mode.get()) {
            case CHAT_THEN_COMMAND -> {
                // Safe helpers keep the acknowledgment chain valid; we only care about ORDER.
                nh.sendChatMessage(message.get() + " #" + i);
                nh.sendChatCommand(command.get().trim());
            }
            case BAD_SIGNATURE -> nh.sendPacket(craft(message.get() + " #" + i, Instant.now(), randomSig()));
            case FUTURE_TIMESTAMP -> nh.sendPacket(craft(message.get() + " #" + i,
                Instant.now().plusSeconds(skewHours.get() * 3600L), randomSig()));
            case PAST_TIMESTAMP -> nh.sendPacket(craft(message.get() + " #" + i,
                Instant.now().minusSeconds(skewHours.get() * 3600L), randomSig()));
        }
    }

    private ChatMessageC2SPacket craft(String text, Instant ts, MessageSignatureData sig) {
        // Empty last-seen acknowledgment (offset 0, no bits, checksum 0). See class caveat.
        LastSeenMessageList.Acknowledgment ack =
            new LastSeenMessageList.Acknowledgment(0, new BitSet(), (byte) 0);
        return new ChatMessageC2SPacket(text, ts, rng.nextLong(), sig, ack);
    }

    private MessageSignatureData randomSig() {
        byte[] b = new byte[MessageSignatureData.SIZE];
        rng.nextBytes(b);
        return new MessageSignatureData(b);
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof DisconnectS2CPacket d) {
            disconnected = true;
            String r = d.reason().getString();
            info("Server disconnected: \"%s\" -> %s", r, KickClassifier.describe(r));
            info("A chat-validation disconnect here means secure chat is ENFORCED (PASS).");
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!disconnected) info("Left the game (likely a validation kick — secure chat enforced, a PASS).");
        if (isActive()) toggle();
    }

    @Override
    public void onDeactivate() { }
}
