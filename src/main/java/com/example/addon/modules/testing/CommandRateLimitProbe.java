package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
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
 * AUDIT: Command Rate-Limit Probe (per-variant bypass detection)
 *
 * Sends each command variant in isolation with a configurable burst, then
 * listens for a server rate-limit response during a settle window. If no
 * response arrives, the variant is marked as a BYPASS — it executed without
 * hitting the limiter.
 *
 * Why rate limits get bypassed:
 *   - Namespace confusion: /sell and /plugin:sell may key on different strings
 *   - Leading space: " sell" may miss the command dispatcher's rate bucket
 *   - Alias vs primary: /essentials:sell is a different handler path
 *   - Mixed case: some limiters are case-sensitive
 *   - Async handling: rate check on main thread, execution off thread
 *
 * The fix is always rate-limit at the RESOLVED command handler, not the raw
 * string — rate-limiting /sell in Paper's general limiter doesn't protect
 * the EssentialsX /sell handler registered under /essentials:sell.
 *
 * Patch signal: at least one untested variant executes with no rate-limit
 * response. The report identifies exactly which ones.
 */
public class CommandRateLimitProbe extends Module {
    private enum Phase { BURST, LISTEN, GAP, DONE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgVariants = this.settings.createGroup("Variants");

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("Base command (no leading slash). e.g. 'sell hand'")
        .defaultValue("sell hand").build()
    );
    private final Setting<String> pluginPrefix = sgGeneral.add(new StringSetting.Builder()
        .name("plugin-prefix")
        .description("Plugin name for namespaced variant (e.g. 'essentials' → /essentials:sell). Leave blank to skip.")
        .defaultValue("essentials").build()
    );
    private final Setting<Integer> spamRate = sgGeneral.add(new IntSetting.Builder()
        .name("spam-rate").description("Commands sent per burst tick for this variant.")
        .defaultValue(20).range(1, 100).sliderRange(1, 50).build()
    );
    private final Setting<Integer> burstTicks = sgGeneral.add(new IntSetting.Builder()
        .name("burst-ticks").description("Ticks to hold the burst before listening.")
        .defaultValue(3).range(1, 20).sliderRange(1, 10).build()
    );
    private final Setting<Integer> listenTicks = sgGeneral.add(new IntSetting.Builder()
        .name("listen-ticks").description("Ticks to wait for a rate-limit response after the burst.")
        .defaultValue(10).range(2, 60).sliderRange(3, 20).build()
    );
    private final Setting<Integer> gapTicks = sgGeneral.add(new IntSetting.Builder()
        .name("gap-ticks").description("Cool-down ticks between variants.")
        .defaultValue(20).range(5, 100).sliderRange(5, 40).build()
    );
    private final Setting<Boolean> burstThenWait = sgGeneral.add(new BoolSetting.Builder()
        .name("burst-then-wait")
        .description("Send a burst, pause, burst again — tests whether the limit resets between bursts.")
        .defaultValue(false).build()
    );

    private final Setting<Boolean> testNormal = sgVariants.add(new BoolSetting.Builder()
        .name("normal").description("/command — baseline.").defaultValue(true).build()
    );
    private final Setting<Boolean> testNamespaced = sgVariants.add(new BoolSetting.Builder()
        .name("namespaced").description("/plugin:command — bypasses string-keyed server limiters.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> testMixedCase = sgVariants.add(new BoolSetting.Builder()
        .name("mixed-case").description("Case-variant (sElL hAnD) — bypasses case-sensitive limiters.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> testUpperCase = sgVariants.add(new BoolSetting.Builder()
        .name("upper-case").description("SELL HAND — another case variant.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> testLeadingSpace = sgVariants.add(new BoolSetting.Builder()
        .name("leading-space").description("' sell hand' — some dispatchers trim, some don't.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> testDoubleSpace = sgVariants.add(new BoolSetting.Builder()
        .name("double-space").description("Doubles the spaces between arguments.")
        .defaultValue(true).build()
    );

    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet summary when done.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private List<String> variants;
    private List<String> labels;
    private List<String> results;

    private int variantIdx;
    private int phaseTick;
    private Phase phase;
    private boolean gotResponse;
    private int burstPhase; // 0 = first burst, 1 = second burst (burst-then-wait)

    public CommandRateLimitProbe() {
        super(AddonTemplate.TESTING_CATEGORY, "command-rate-limit-probe",
            "Probes each command variant in isolation, detects which bypass rate limits. Reports BYPASSED vs. LIMITED per variant.");
    }

    @Override
    public void onActivate() {
        String cmd = command.get();
        String plug = pluginPrefix.get().trim();

        variants = new ArrayList<>();
        labels   = new ArrayList<>();
        if (testNormal.get())      { variants.add(cmd);              labels.add("/" + cmd.split(" ")[0]); }
        if (testNamespaced.get() && !plug.isEmpty()) {
            String ns = plug + ":" + cmd.split(" ")[0] + (cmd.contains(" ") ? " " + cmd.substring(cmd.indexOf(' ') + 1) : "");
            variants.add(ns); labels.add("/" + ns.split(" ")[0]);
        }
        if (testMixedCase.get())   { variants.add(mixedCase(cmd));   labels.add("mIxEdCaSe"); }
        if (testUpperCase.get())   { variants.add(cmd.toUpperCase()); labels.add("UPPERCASE"); }
        if (testLeadingSpace.get()){ variants.add(" " + cmd);        labels.add("' " + cmd.split(" ")[0] + "'"); }
        if (testDoubleSpace.get()) { variants.add(cmd.replace(" ", "  ")); labels.add("double-space"); }

        results = new ArrayList<>();
        variantIdx = 0; phaseTick = 0; burstPhase = 0; ticksActive = 0; packetsSent = 0;
        phase = variants.isEmpty() ? Phase.DONE : Phase.BURST;
        gotResponse = false;
        if (variants.isEmpty()) { error("All variants disabled."); toggle(); return; }
        info("Probing %d variant(s) of '%s'…", variants.size(), cmd.split(" ")[0]);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || phase == Phase.DONE) return;
        ticksActive++;
        phaseTick++;

        String variant = variants.get(variantIdx);
        String label = labels.get(variantIdx);

        switch (phase) {
            case BURST -> {
                // Send the burst each tick of this phase
                for (int i = 0; i < spamRate.get(); i++) {
                    mc.player.networkHandler.sendChatCommand(variant.trim());
                    packetsSent++;
                }
                if (phaseTick >= burstTicks.get()) {
                    if (burstThenWait.get() && burstPhase == 0) {
                        // go to a short listen pause then burst again
                        burstPhase = 1;
                        phase = Phase.LISTEN;
                        phaseTick = 0;
                    } else {
                        burstPhase = 0;
                        phase = Phase.LISTEN;
                        phaseTick = 0;
                    }
                }
            }
            case LISTEN -> {
                int listenDur = (burstThenWait.get() && burstPhase == 1) ? listenTicks.get() / 2 : listenTicks.get();
                if (phaseTick >= listenDur) {
                    if (burstThenWait.get() && burstPhase == 1) {
                        // Do second burst
                        burstPhase = 2;
                        phase = Phase.BURST;
                        phaseTick = 0;
                        gotResponse = false; // reset for second burst
                    } else {
                        // Record result
                        String outcome = gotResponse ? "LIMITED   " : "NOT LIMITED → bypass";
                        results.add(String.format("  %-28s  %s", label + " x" + spamRate.get(), outcome));
                        info("%-28s  %s", label, outcome);
                        phase = Phase.GAP;
                        phaseTick = 0;
                        gotResponse = false;
                        burstPhase = 0;
                    }
                }
            }
            case GAP -> {
                if (phaseTick >= gapTicks.get()) {
                    variantIdx++;
                    if (variantIdx >= variants.size()) finish();
                    else { phase = Phase.BURST; phaseTick = 0; }
                }
            }
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (phase != Phase.LISTEN) return;
        if (event.packet instanceof GameMessageS2CPacket msg) {
            String txt = msg.content().getString().toLowerCase();
            // Heuristic: server rate-limit messages usually contain words like
            // "slow down", "too fast", "rate", "wait", "limit", "cooldown", "denied"
            if (txt.contains("slow") || txt.contains("fast") || txt.contains("rate")
                || txt.contains("wait") || txt.contains("limit") || txt.contains("cool")
                || txt.contains("denied") || txt.contains("cannot") || txt.contains("spam")) {
                gotResponse = true;
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (!results.isEmpty()) {
            info("=== CommandRateLimitProbe report ===");
            for (String r : results) info(r);
            long bypassed = results.stream().filter(r -> r.contains("bypass")).count();
            info("Bypassed: %d / %d", bypassed, results.size());
        }
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    private void finish() {
        phase = Phase.DONE;
        onDeactivate(); // print before toggle clears results
        toggle();
    }

    private static String mixedCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
            sb.append(i % 2 == 0 ? Character.toLowerCase(s.charAt(i)) : Character.toUpperCase(s.charAt(i)));
        return sb.toString();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }
}
