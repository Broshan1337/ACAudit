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
 * AUDIT: Two-Window Race
 *
 * With a plugin container open, fires a click on a CONTAINER slot and a click on
 * a PLAYER-INVENTORY slot within the same tick. Targets the classic trade/sell
 * dupe where the plugin snapshots one inventory (the GUI) but reads/writes the
 * other (your real inventory) without locking both - so you move an item out of
 * your inventory at the same instant the plugin consumes it from the GUI view.
 *
 * Patch signal: a single lock guarding BOTH the GUI handler and the backing
 * player inventory for the duration of one click's processing; validate the item
 * is still present in its real slot at commit time, not at snapshot time.
 */
public class TwoWindowRace extends Module {
    public enum Action { PICKUP, QUICK_MOVE, THROW }
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> containerSlot = sgGeneral.add(new IntSetting.Builder()
        .name("container-slot").description("Slot in the open GUI to act on.")
        .defaultValue(0).range(0, 200).sliderRange(0, 53).build()
    );
    private final Setting<Integer> playerSlot = sgGeneral.add(new IntSetting.Builder()
        .name("player-slot").description("Slot in your inventory to act on the same tick.")
        .defaultValue(44).range(0, 200).sliderRange(0, 53).build()
    );
    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action").defaultValue(Action.QUICK_MOVE).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one same-tick pair (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_N))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build()
    );
    private final Setting<Integer> attempts = sgGeneral.add(new IntSetting.Builder()
        .name("attempts").description("Pairs per fire.")
        .defaultValue(1).range(1, 50).sliderRange(1, 10).build()
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

    public TwoWindowRace() {
        super(AddonTemplate.DUPE_CATEGORY, "two-window-race",
            "Clicks a container slot and a player-inventory slot in the same tick. Tests cross-inventory locking in trade/sell GUIs.");
    }

    @Override
    public void onActivate() { wasPressed = false; }

    private SlotActionType act() {
        return switch (action.get()) {
            case PICKUP -> SlotActionType.PICKUP;
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

        int syncId = mc.player.currentScreenHandler.syncId;
        int rev = mc.player.currentScreenHandler.getRevision();

        for (int i = 0; i < attempts.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) (int) containerSlot.get(), (byte) 0, act(),
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) (int) playerSlot.get(), (byte) 0, act(),
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
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
