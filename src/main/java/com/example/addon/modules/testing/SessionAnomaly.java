package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.KickClassifier;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.c2s.config.ReadyC2SPacket;
import net.minecraft.network.packet.c2s.play.AcknowledgeChunksC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;

/**
 * AUDIT: Session / Protocol-State Anomaly
 *
 * <p>Probes the connection-level state machine rather than gameplay:
 * <ul>
 *   <li>{@code NEVER_ACK_CHUNKS} — SUPPRESS the client's outbound chunk acknowledgments
 *       ({@link AcknowledgeChunksC2SPacket}, Paper/vanilla flow-control) while you keep moving and
 *       interacting. A correct server throttles or eventually force-closes a client that never
 *       acknowledges chunks; a lax one keeps streaming and lets you interact with chunks it thinks
 *       you never loaded. (This is the one genuinely novel ack vector — "acknowledge fake/duplicate
 *       chunks" is a misread of the protocol: the packet is a single desired-rate float, already
 *       abused by chunk-ack-spoof.)</li>
 *   <li>{@code CLIENT_SETTINGS_SPAM} — resend {@link ClientOptionsC2SPacket} every tick; tests
 *       whether the server re-applies/reallocates per update or only at login.</li>
 *   <li>{@code CONFIG_IN_PLAY} — attempt to send a CONFIG-phase packet during PLAY (protocol-state
 *       confusion). Expected result on vanilla protocol: the client's play encoder REJECTS it
 *       locally (the state machine won't serialize a config packet in play) — an honest negative
 *       that documents this isn't client-reachable. If it somehow sends, watch for a kick.</li>
 * </ul>
 *
 * <p>Resource-pack response manipulation lives in {@code resource-pack-desync} — not duplicated.
 * Patch signal: enforce the connection state machine and flow-control server-side; bound and
 * validate late settings updates. Non-op, client-side. Own server only.
 */
public class SessionAnomaly extends Module {
    public enum Mode { NEVER_ACK_CHUNKS, CLIENT_SETTINGS_SPAM, CONFIG_IN_PLAY }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which session/protocol-state anomaly to run.")
        .defaultValue(Mode.NEVER_ACK_CHUNKS).build());
    private final Setting<Integer> settingsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("settings-per-tick").description("CLIENT_SETTINGS_SPAM: ClientOptions packets per tick.")
        .defaultValue(10).range(1, 200).sliderRange(1, 50)
        .visible(() -> mode.get() == Mode.CLIENT_SETTINGS_SPAM).build());
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable-on-kick").description("Disable when kicked (server enforced — a PASS).")
        .defaultValue(true).build());

    private int acksSuppressed = 0, sent = 0;

    public SessionAnomaly() {
        super(AddonTemplate.TESTING_CATEGORY, "session-anomaly",
            "Connection-level probes: suppress chunk acks while interacting, spam client settings, or attempt a config-phase packet in play.");
    }

    @Override
    public void onActivate() {
        acksSuppressed = sent = 0;
        if (mc.player == null) { warning("Not in game."); toggle(); return; }
        info("Mode %s.", mode.get());
        if (mode.get() == Mode.NEVER_ACK_CHUNKS)
            info("Chunk acks are now being SUPPRESSED — move around / interact and watch for a force-close or continued streaming.");
        if (mode.get() == Mode.CONFIG_IN_PLAY) tryConfigInPlay();
    }

    private void tryConfigInPlay() {
        try {
            mc.player.networkHandler.sendPacket(ReadyC2SPacket.INSTANCE);
            info("Config-phase ReadyC2SPacket was accepted by the client encoder and sent — watch for a server kick (protocol violation).");
        } catch (Throwable t) {
            info("Client play-encoder REJECTED the config packet locally (%s) — protocol-state confusion is NOT client-reachable on vanilla protocol. Honest negative.",
                t.getClass().getSimpleName());
        }
        info("CONFIG_IN_PLAY is one-shot — disable now.");
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (mode.get() == Mode.NEVER_ACK_CHUNKS && event.packet instanceof AcknowledgeChunksC2SPacket) {
            event.cancel();
            acksSuppressed++;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (mode.get() == Mode.CLIENT_SETTINGS_SPAM) {
            for (int i = 0; i < settingsPerTick.get(); i++) {
                mc.player.networkHandler.sendPacket(new ClientOptionsC2SPacket(SyncedClientOptions.createDefault()));
                sent++;
            }
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof DisconnectS2CPacket d)
            info("Disconnect: %s", KickClassifier.describe(d.reason().getString()));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (mode.get() == Mode.NEVER_ACK_CHUNKS)
            info("Force-closed after suppressing %d chunk acks — the server enforced flow-control (a PASS).", acksSuppressed);
        if (autoDisable.get() && isActive()) toggle();
    }

    @Override
    public void onDeactivate() {
        if (mode.get() == Mode.NEVER_ACK_CHUNKS)
            info("Summary: suppressed %d chunk acks. No force-close = the server did NOT enforce chunk-ack flow-control (finding).", acksSuppressed);
        if (mode.get() == Mode.CLIENT_SETTINGS_SPAM)
            info("Summary: sent %d ClientOptions packets.", sent);
    }
}
