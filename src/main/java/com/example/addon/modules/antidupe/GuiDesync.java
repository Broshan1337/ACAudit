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
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: GUI Desync (container blink + auto-grab)
 *
 * Blink, but for inventory packets. Holds your outbound ClickSlot/Close packets
 * so the server is behind your client GUI state, then releases them in a burst
 * on demand. With auto-grab, it quick-moves the open container's item slots into
 * your inventory while the packets are held - the "lift the item out of the GUI
 * while the server hasn't caught up" theft pattern.
 *
 * What it tests: does the plugin process GUI clicks against a server-authoritative
 * locked snapshot, or trust receipt order and client state? If holding+releasing
 * lets a take commit without the matching charge/reclaim, the handler is TOCTOU.
 *
 * Patch signal: lock the inventory for the duration of one click's processing;
 * validate the claimed source still holds the item at commit; never charge or
 * reclaim based on close-packet timing - make it atomic with the take.
 */
public class GuiDesync extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> holdClicks = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-clicks").description("Hold outbound ClickSlot packets.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> holdClose = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-close").description("Hold the window-close packet.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> autoGrab = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-grab").description("Quick-move container item slots into your inventory while held.")
        .defaultValue(true).build()
    );
    private final Setting<Integer> maxHold = sgGeneral.add(new IntSetting.Builder()
        .name("max-hold-ticks").description("Auto-release after this many ticks.")
        .defaultValue(40).range(1, 600).sliderRange(10, 200).build()
    );
    private final Setting<Keybind> releaseKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("release-key").description("Press to flush the held packets immediately.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_J)).build()
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

    private final List<Packet<?>> queue = new ArrayList<>();
    private int held = 0;
    private boolean wasPressed = false;
    private PacketEvent.Send lastEvent;

    public GuiDesync() {
        super(AddonTemplate.DUPE_CATEGORY, "gui-desync",
            "Holds container click/close packets then bursts them (auto-grab optional). Tests GUI TOCTOU / snapshot locking.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; queue.clear(); held = 0; wasPressed = false;
        info("Tip: combine with relog-dupe — hold packets and force-disconnect to test save-on-quit race.");
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent); flush(); }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        boolean hold = (holdClicks.get() && event.packet instanceof ClickSlotC2SPacket)
            || (holdClose.get() && event.packet instanceof CloseHandledScreenC2SPacket);
        if (!hold) return;
        lastEvent = event;
        queue.add(event.packet);
        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;

        boolean p = releaseKey.get().isPressed();
        if (p && !wasPressed) { flush(); wasPressed = true; return; }
        wasPressed = p;

        if (autoGrab.get()) grabItemSlots();

        held++;
        if (held >= maxHold.get()) flush();
    }

    private void grabItemSlots() {
        var handler = mc.player.currentScreenHandler;
        if (handler == null) return;
        int containerSlots = handler.slots.size() - 36;
        if (containerSlots <= 0) return;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            // generates a ClickSlot which onSend() will hold
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) i, (byte) 0, SlotActionType.QUICK_MOVE,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
    }

    private void flush() {
        if (queue.isEmpty()) { held = 0; return; }
        info("Releasing %d held container packets", queue.size());
        for (Packet<?> pkt : queue) {
            if (lastEvent != null) lastEvent.sendSilently(pkt);
            else if (mc.player != null) mc.player.networkHandler.getConnection().send(pkt);
        }
        queue.clear();
        held = 0;
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
