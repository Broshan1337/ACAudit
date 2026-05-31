package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.LecternScreen;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

public class LecternCrash extends Module {
    public LecternCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "lectern-crash",
            "Sends a QUICK_MOVE click on a lectern's virtual slot when it opens. Tests lectern slot handling.");
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!(event.screen instanceof LecternScreen)) return;
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            mc.player.currentScreenHandler.syncId, mc.player.currentScreenHandler.getRevision(),
            (short) 0, (byte) 0, SlotActionType.QUICK_MOVE,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        toggle();
    }
}
