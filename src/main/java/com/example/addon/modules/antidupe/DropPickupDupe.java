package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

public class DropPickupDupe extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> targetSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Inventory slot to drop+pickup.")
        .defaultValue(0).range(0, 44).sliderRange(0, 44).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public DropPickupDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "drop-pickup-dupe",
            "THROW+PICKUP on same slot with revision=0. Tests stale-revision rejection.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        int  syncId = mc.player.currentScreenHandler.syncId;
        short slot  = (short) (int) targetSlot.get();
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, 0, slot, (byte) 0, SlotActionType.THROW,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, 0, slot, (byte) 0, SlotActionType.PICKUP,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
