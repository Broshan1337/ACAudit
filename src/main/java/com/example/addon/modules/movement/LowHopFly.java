package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class LowHopFly extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> hoverHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("hover-height").description("Blocks above ground to hover at.")
        .defaultValue(0.42).range(0.1, 2.0).sliderRange(0.1, 1.0).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private double targetY = -1;

    public LowHopFly() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "low-hop-fly",
            "Hovers with onGround=true. Tests server-side ground validation.");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) targetY = mc.player.getY() + hoverHeight.get();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);
        mc.player.setPosition(mc.player.getX(), targetY, mc.player.getZ());
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            mc.player.getX(), targetY, mc.player.getZ(), true, mc.player.horizontalCollision));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
