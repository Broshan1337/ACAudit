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
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Inventory Sweep
 *
 * Auto-iterates every slot in the open container, sending the chosen action at
 * each - no slot ids to configure. Surfaces per-slot handling gaps: a plugin
 * that locks the "buy" button but forgets a decorative/filler slot, or a
 * quick-move path that lands an item in two places.
 *
 * Patch signal: validate every slot through the same lock and the same
 * authority check; no slot should be a special case that skips reconciliation.
 */
public class InventorySweep extends Module {
    public enum Action { PICKUP, RIGHT, QUICK_MOVE, THROW }
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action").defaultValue(Action.QUICK_MOVE).build()
    );
    private final Setting<Boolean> containerOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("container-only").description("Only sweep the container's slots, not your inventory.")
        .defaultValue(true).build()
    );
    private final Setting<Integer> clicksPerSlot = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-slot").defaultValue(1).range(1, 50).sliderRange(1, 20).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Sweep all slots once (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_K))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build()
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

    public InventorySweep() {
        super(AddonTemplate.DUPE_CATEGORY, "inventory-sweep",
            "Sends the chosen action at every slot in the open GUI. Tests uniform per-slot validation. No slot config.");
    }

    @Override
    public void onActivate() { wasPressed = false; }

    private byte button() { return (byte) (action.get() == Action.RIGHT ? 1 : 0); }
    private SlotActionType act() {
        return switch (action.get()) {
            case PICKUP, RIGHT -> SlotActionType.PICKUP;
            case QUICK_MOVE -> SlotActionType.QUICK_MOVE;
            case THROW -> SlotActionType.THROW;
        };
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;

        boolean fire;
        if (trigger.get() == Trigger.EACH_TICK) fire = true;
        else { boolean p = key.get().isPressed(); fire = p && !wasPressed; wasPressed = p; }
        if (!fire) return;

        var handler = mc.player.currentScreenHandler;
        int total = handler.slots.size();
        int hi = containerOnly.get() ? Math.max(0, total - 36) : total;
        int syncId = handler.syncId;
        int rev = handler.getRevision();

        for (int slot = 0; slot < hi; slot++) {
            for (int c = 0; c < clicksPerSlot.get(); c++) {
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) slot, button(), act(),
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
            }
        }
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
