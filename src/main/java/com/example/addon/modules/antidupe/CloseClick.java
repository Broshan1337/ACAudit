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
    public enum Action { PICKUP, QUICK_MOVE, THROW }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Slot to click in the same tick as the close.")
        .defaultValue(0).range(0, 200).sliderRange(0, 53).build()
    );
    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action").description("Click action type to send.")
        .defaultValue(Action.PICKUP).build()
    );
    private final Setting<Integer> burst = sgGeneral.add(new IntSetting.Builder()
        .name("burst").description("Click packets sent alongside the close.")
        .defaultValue(3).range(1, 50).sliderRange(1, 20).build()
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
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    private boolean wasPressed = false;

    public CloseClick() {
        super(AddonTemplate.DUPE_CATEGORY, "close-click",
            "Sends a click and the close packet in the same tick. Tests synchronous, atomic window teardown.");
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

        int syncId = mc.player.currentScreenHandler.syncId;
        int rev = mc.player.currentScreenHandler.getRevision();
        SlotActionType act = switch (action.get()) {
            case PICKUP -> SlotActionType.PICKUP;
            case QUICK_MOVE -> SlotActionType.QUICK_MOVE;
            case THROW -> SlotActionType.THROW;
        };
        CloseHandledScreenC2SPacket close = new CloseHandledScreenC2SPacket(syncId);

        if (order.get() == Order.CLOSE_THEN_CLICK) {
            mc.player.networkHandler.sendPacket(close);
            packetsSent++;
            for (int i = 0; i < burst.get(); i++) {
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) (int) slot.get(), (byte) 0, act,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
                packetsSent++;
            }
        } else {
            for (int i = 0; i < burst.get(); i++) {
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) (int) slot.get(), (byte) 0, act,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
                packetsSent++;
            }
            mc.player.networkHandler.sendPacket(close);
            packetsSent++;
        }
        info("Sent %s x%d %s on slot %d (syncId %d)", order.get(), burst.get(), action.get(), slot.get(), syncId);
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
