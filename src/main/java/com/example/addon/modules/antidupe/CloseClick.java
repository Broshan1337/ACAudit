package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Close-Click (simultaneous)
 *
 * Fires a slot click and the window-close packet in the SAME tick (and in
 * close-then-click and click-then-close orderings). Targets plugins whose GUI
 * teardown runs asynchronously or a tick late, leaving a window where a click
 * is accepted against a container the plugin believes is already closed - a
 * common source of "claim the item and also keep it" dupes.
 *
 * Distinct from container-exploit (which tests POST-close clicks): this tests
 * the exact-same-tick race.
 *
 * Patch signal: process close synchronously and atomically invalidate the
 * window id before returning; reject any click whose window id was closed this
 * tick or earlier.
 */
public class CloseClick extends Module {
    public enum Order { CLOSE_THEN_CLICK, CLICK_THEN_CLOSE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Slot to click in the same tick as the close.")
        .defaultValue(0).range(0, 200).sliderRange(0, 53).build()
    );
    private final Setting<Order> order = sgGeneral.add(new EnumSetting.Builder<Order>()
        .name("order").description("Packet ordering within the tick.")
        .defaultValue(Order.CLICK_THEN_CLOSE).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one close+click sequence.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_M)).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private boolean wasPressed = false;

    public CloseClick() {
        super(AddonTemplate.DUPE_CATEGORY, "close-click",
            "Sends a click and the close packet in the same tick. Tests synchronous, atomic window teardown.");
    }

    @Override
    public void onActivate() { wasPressed = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        int syncId = mc.player.currentScreenHandler.syncId;
        int rev = mc.player.currentScreenHandler.getRevision();
        ClickSlotC2SPacket click = new ClickSlotC2SPacket(
            syncId, rev, (short) (int) slot.get(), (byte) 0, SlotActionType.PICKUP,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY);
        CloseHandledScreenC2SPacket close = new CloseHandledScreenC2SPacket(syncId);

        if (order.get() == Order.CLOSE_THEN_CLICK) {
            mc.player.networkHandler.sendPacket(close);
            mc.player.networkHandler.sendPacket(click);
        } else {
            mc.player.networkHandler.sendPacket(click);
            mc.player.networkHandler.sendPacket(close);
        }
        info("Sent %s on slot %d (syncId %d)", order.get(), slot.get(), syncId);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
