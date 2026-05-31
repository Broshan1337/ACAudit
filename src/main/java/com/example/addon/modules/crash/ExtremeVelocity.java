package com.example.addon.modules.crash;

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

public class ExtremeVelocity extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> distancePerTick = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance-per-tick").description("Blocks to claim to move per tick.")
        .defaultValue(1000.0).range(10.0, 1_000_000.0).sliderRange(10.0, 10_000.0).build()
    );
    private final Setting<Integer> packetsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Position packets per tick.")
        .defaultValue(1).range(1, 20).sliderRange(1, 10).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private double accumX = 0;

    public ExtremeVelocity() {
        super(AddonTemplate.CRASH_CATEGORY, "extreme-velocity",
            "Claims supersonic movement per tick. Tests chunk-load flooding and speed-cap enforcement.");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) accumX = mc.player.getX();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        double step = distancePerTick.get() / packetsPerTick.get();
        double y = mc.player.getY(), z = mc.player.getZ();
        for (int i = 0; i < packetsPerTick.get(); i++) {
            accumX += step;
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                accumX, y, z, true, false));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
