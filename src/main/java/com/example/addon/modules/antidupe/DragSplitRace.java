package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Drag-Split Race (mid-drag inventory close)
 *
 * A drag-split operation (right-click drag) uses three QUICK_CRAFT packets:
 *   button 0 = begin drag, button 1 = add slot, button 2 = end (commit split)
 *
 * This module starts a drag across a configurable set of slots, then CLOSES the
 * inventory mid-drag — before the end packet — so the server must clean up an
 * incomplete drag state without applying a partial split. On some implementations
 * the cleanup runs asynchronously and the pending split can be committed after the
 * window is already closed, distributing items to slots in a container the player
 * no longer has open.
 *
 * Patch signal: on window close (or any other interruption), atomically discard
 * any in-progress drag state immediately — the items on the virtual cursor must
 * be returned to the inventory before the close is acknowledged.
 *
 * Grab a stack, open a container, press the key. Run against your OWN server.
 */
public class DragSplitRace extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> dragSlots = sgGeneral.add(new IntSetting.Builder()
        .name("drag-slots").description("Number of slots to spread the drag across (starting from slot 0).")
        .defaultValue(5).range(1, 27).sliderRange(1, 27).build()
    );
    private final Setting<Boolean> closeAfter = sgGeneral.add(new BoolSetting.Builder()
        .name("close-mid-drag").description("Close the container after drag-start+add but before drag-end.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> sendEnd = sgGeneral.add(new BoolSetting.Builder()
        .name("send-end-too").description("Also send the drag-end packet in the same tick (before or after close).")
        .defaultValue(true).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Press to fire the drag-split + close sequence.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F)).build()
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

    private boolean wasPressed = false;

    public DragSplitRace() {
        super(AddonTemplate.DUPE_CATEGORY, "drag-split-race",
            "Starts a right-click drag then closes the container mid-drag. Tests drag-state cleanup on window close.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; wasPressed = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        int slots = Math.min(dragSlots.get(), handler.slots.size() - 37);

        // Begin right-click drag (button 4 = right-click begin)
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, rev, (short) -999, (byte) 4, SlotActionType.QUICK_CRAFT,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;

        // Add each slot to the drag (button 5 = right-click add slot)
        for (int i = 0; i < slots; i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) i, (byte) 5, SlotActionType.QUICK_CRAFT,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }

        // Close mid-drag (before end packet)
        if (closeAfter.get()) {
            mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(syncId));
            packetsSent++;
        }

        // Also send end — if close already processed, this hits after teardown
        if (sendEnd.get()) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) -999, (byte) 6, SlotActionType.QUICK_CRAFT,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
        info("Drag-split fired: %d slots, close=%s, end=%s", slots, closeAfter.get(), sendEnd.get());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket p)
            info("Server updated slot %d → %s (syncId %d)", p.getSlot(), p.getStack().getName().getString(), p.getSyncId());
        else if (event.packet instanceof InventoryS2CPacket p)
            info("Server resynced inventory (syncId %d, %d slots)", p.syncId(), p.contents().size());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
