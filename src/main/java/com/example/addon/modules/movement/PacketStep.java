package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class PacketStep extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> stepHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("step-height").description("Y delta to claim each step packet.")
        .defaultValue(1.0).range(0.1, 5.0).sliderRange(0.1, 3.0).build()
    );
    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets").description("Position packets to send per key press.")
        .defaultValue(3).range(1, 20).sliderRange(1, 10).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public PacketStep() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "packet-step",
            "Spoofs Y position upward without a jump arc. Tests path-continuity validation.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.options.jumpKey.isPressed()) return;
        double y = mc.player.getY();
        for (int i = 0; i < packets.get(); i++) {
            y += stepHeight.get();
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX(), y, mc.player.getZ(), true, false));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
