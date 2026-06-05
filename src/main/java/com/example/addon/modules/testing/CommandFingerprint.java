package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.DetectedPlugins;
import com.mojang.brigadier.suggestion.Suggestion;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AUDIT: Command Fingerprint (plugin enumeration via command feedback)
 *
 * PLATFORM: Paper-specific (Bukkit plugin commands).
 *
 * A client can ask the server to complete a partial command. The server replies
 * with the matching command names — and on a Bukkit server those include
 * plugin-namespaced aliases (essentials:pay, cmi:money, vault:..., grim:...,
 * worldguard:...). Walking the alphabet of partial commands and collecting the
 * suggestions enumerates exactly which plugins (and often which versions, from
 * their help output) are installed, which tells a server owner precisely how much
 * their stack reveals to any connected client. This is recon, fully client-side.
 *
 *   What it exposes: the installed plugin set, leaked to any player via tab
 *     completion and command feedback.
 *   Patch signal (any well-implemented setup): restrict command tab-completion and
 *     help visibility to permitted commands per player; do not advertise
 *     plugin-namespaced aliases to clients without permission.
 *
 * Run on YOUR server to see what your stack discloses.
 */
public class CommandFingerprint extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<String> prefix = sgGeneral.add(new StringSetting.Builder()
        .name("prefix").description("Partial command to complete. '/' lists root commands; a letter narrows it.")
        .defaultValue("/").build()
    );
    private final Setting<Boolean> sweepAlphabet = sgGeneral.add(new BoolSetting.Builder()
        .name("sweep-alphabet").description("Cycle '/a'../z' to enumerate the full command set, not just the prefix.")
        .defaultValue(true).build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks between completion requests.")
        .defaultValue(8).range(1, 100).sliderRange(2, 40).build()
    );

    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print the discovered command set on deactivate.")
        .defaultValue(true).build()
    );

    private final Set<String> discovered = new LinkedHashSet<>();
    private int completionId = 0, timer = 0, sweepIdx = 0;

    public CommandFingerprint() {
        super(AddonTemplate.TESTING_CATEGORY, "command-fingerprint",
            "Enumerates installed plugins via command tab-completion (plugin-namespaced aliases). Client-side recon of what your stack discloses.");
    }

    @Override
    public void onActivate() { discovered.clear(); completionId = 0; timer = 0; sweepIdx = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (timer > 0) { timer--; return; }
        timer = delayTicks.get();

        String partial;
        if (sweepAlphabet.get()) { partial = "/" + (char) ('a' + (sweepIdx % 26)); sweepIdx++; }
        else partial = prefix.get();

        mc.player.networkHandler.sendPacket(new RequestCommandCompletionsC2SPacket(completionId++, partial));
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof CommandSuggestionsS2CPacket s)) return;
        int before = discovered.size();
        for (Suggestion sug : s.getSuggestions().getList()) {
            String t = sug.getText();
            if (discovered.add(t)) {
                // Highlight namespaced aliases (the strongest plugin tell) and record
                // the namespace so the plugin-aware fuzzer can target it.
                int colon = t.indexOf(':');
                if (colon > 0) {
                    String ns = t.substring(t.startsWith("/") ? 1 : 0, colon);
                    info("plugin command: %s", t);
                    DetectedPlugins.record(ns, DetectedPlugins.Confidence.CONFIRMED);
                }
            }
        }
        if (discovered.size() > before && discovered.size() % 25 == 0)
            info("Discovered %d commands so far...", discovered.size());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Discovered %d commands total.", discovered.size());
            Set<String> plugins = new LinkedHashSet<>();
            for (String c : discovered) {
                int i = c.indexOf(':');
                if (i > 0) plugins.add(c.substring(c.startsWith("/") ? 1 : 0, i));
            }
            if (!plugins.isEmpty()) info("Inferred plugin namespaces: %s", String.join(", ", plugins));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }
}
