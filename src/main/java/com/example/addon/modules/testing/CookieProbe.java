package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.common.CookieResponseC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.util.Identifier;

import java.util.Random;

/**
 * AUDIT: Cookie Probe (proxy cookie handling, 1.20.5+)
 *
 * PLATFORM: Paper-specific (Velocity/Paper cookie & transfer system).
 *
 * Cookies (CookieResponseC2SPacket) let a proxy store persistent per-client data
 * and survive server transfers — Velocity uses them for forwarding/session data.
 * The client normally replies only when the server requests a cookie, but the
 * packet is valid to send unsolicited; a server or proxy that trusts incoming
 * cookies without binding them to a request, or that mishandles oversized/forged
 * cookie payloads, exposes a new surface that did not exist before 1.20.5. This
 * sends forged cookies on plausible keys with garbage/oversized payloads and
 * watches for disconnects or anomalies.
 *
 *   What it exploits: a backend/proxy acting on client-supplied cookie data it did
 *     not request, or failing to bound cookie payload size.
 *   Patch signal (any well-implemented setup): only accept a cookie response that
 *     matches an outstanding request, bound the payload size, and never trust cookie
 *     contents as authenticated proxy state.
 *
 * Run on YOUR network only.
 */
public class CookieProbe extends Module {
    public enum Mode { FORGED_KEYS, OVERSIZED, EMPTY, RANDOM }

    private static final Identifier[] KEYS = {
        Identifier.of("velocity", "secret"),
        Identifier.of("bungeecord", "cookie"),
        Identifier.of("acaudit", "probe"),
        Identifier.of("minecraft", "session"),
    };

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("FORGED_KEYS=plausible keys w/ small body; OVERSIZED=large body; EMPTY=zero-length; RANDOM=random key+body.")
        .defaultValue(Mode.FORGED_KEYS).build()
    );
    private final Setting<Integer> bodyBytes = sgGeneral.add(new IntSetting.Builder()
        .name("body-bytes").description("Payload size for OVERSIZED mode.")
        .defaultValue(5120).range(0, 65535).sliderRange(0, 32768)
        .visible(() -> mode.get() == Mode.OVERSIZED).build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks between cookie sends.")
        .defaultValue(20).range(1, 200).sliderRange(5, 100).build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private final Random rng = new Random();
    private int timer = 0, sent = 0, index = 0;

    public CookieProbe() {
        super(AddonTemplate.TESTING_CATEGORY, "cookie-probe",
            "Sends forged/oversized cookie responses (1.20.5+ proxy cookie system). Tests whether the server/proxy trusts unrequested cookie data.");
    }

    @Override
    public void onActivate() { timer = 0; sent = 0; index = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (timer > 0) { timer--; return; }
        timer = delayTicks.get();

        Identifier key;
        byte[] body;
        switch (mode.get()) {
            case OVERSIZED -> { key = KEYS[index % KEYS.length]; body = new byte[bodyBytes.get()]; rng.nextBytes(body); }
            case EMPTY -> { key = KEYS[index % KEYS.length]; body = new byte[0]; }
            case RANDOM -> { key = Identifier.of("acaudit", "r" + rng.nextInt(100000)); body = new byte[rng.nextInt(64)]; rng.nextBytes(body); }
            default -> { key = KEYS[index % KEYS.length]; body = new byte[]{1, 2, 3, 4}; }
        }
        mc.player.networkHandler.sendPacket(new CookieResponseC2SPacket(key, body));
        sent++;
        info("Cookie -> %s (%d bytes)", key, body.length);
        index++;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof DisconnectS2CPacket d)
            warning("Disconnected after cookie probe: \"%s\"", d.reason().getString());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d cookies sent.", sent);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
