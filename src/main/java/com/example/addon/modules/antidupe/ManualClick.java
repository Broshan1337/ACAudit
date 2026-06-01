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
 * AUDIT: Manual Click (bring-your-own-payload)
 *
 * Full manual control over a single ClickSlotC2SPacket: you set the syncId,
 * revision, slot, button, and action type by hand, then fire N copies on a
 * keybind or every tick. This is the "craft any container interaction" tool -
 * use it to reproduce a specific exploit sequence, probe a plugin's handling of
 * a particular (slot, button, action) combo, or replay an edge case the preset
 * dupe modules don't cover.
 *
 * Every field is exposed deliberately so you can send things the vanilla client
 * never would (out-of-range slots, mismatched syncId, stale revision, unusual
 * action/button pairs) and observe how your server validates them.
 */
public class ManualClick extends Module {
    public enum SyncMode { CURRENT, CUSTOM }
    public enum RevMode { CURRENT, ZERO, CUSTOM }
    public enum Action { PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL }
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgPacket = this.settings.getDefaultGroup();
    private final SettingGroup sgFire = this.settings.createGroup("Fire");

    // --- packet fields ---
    private final Setting<SyncMode> syncMode = sgPacket.add(new EnumSetting.Builder<SyncMode>()
        .name("sync-id-mode").description("Use the open container's syncId, or a custom one.")
        .defaultValue(SyncMode.CURRENT).build()
    );
    private final Setting<Integer> customSyncId = sgPacket.add(new IntSetting.Builder()
        .name("custom-sync-id").description("syncId when mode is CUSTOM.")
        .defaultValue(0).range(0, 127).sliderRange(0, 127)
        .visible(() -> syncMode.get() == SyncMode.CUSTOM).build()
    );
    private final Setting<RevMode> revMode = sgPacket.add(new EnumSetting.Builder<RevMode>()
        .name("revision-mode").description("CURRENT = valid, ZERO = stale, CUSTOM = your value.")
        .defaultValue(RevMode.CURRENT).build()
    );
    private final Setting<Integer> customRev = sgPacket.add(new IntSetting.Builder()
        .name("custom-revision").description("Revision when mode is CUSTOM.")
        .defaultValue(0).range(0, 99999).sliderRange(0, 200)
        .visible(() -> revMode.get() == RevMode.CUSTOM).build()
    );
    private final Setting<Integer> slot = sgPacket.add(new IntSetting.Builder()
        .name("slot").description("Slot index (negative / OOB allowed for probing).")
        .defaultValue(0).range(-128, 32767).sliderRange(-10, 100).build()
    );
    private final Setting<Integer> button = sgPacket.add(new IntSetting.Builder()
        .name("button").description("Button byte (0=left, 1=right, hotbar 0-8 for SWAP, etc.).")
        .defaultValue(0).range(-128, 127).sliderRange(0, 8).build()
    );
    private final Setting<Action> action = sgPacket.add(new EnumSetting.Builder<Action>()
        .name("action").description("SlotActionType to send.")
        .defaultValue(Action.PICKUP).build()
    );

    // --- firing ---
    private final Setting<Trigger> trigger = sgFire.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").description("Fire on keybind press, or every tick.")
        .defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgFire.add(new KeybindSetting.Builder()
        .name("key").description("Key that fires the burst (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_B))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build()
    );
    private final Setting<Integer> count = sgFire.add(new IntSetting.Builder()
        .name("count").description("Copies of the packet to send per fire.")
        .defaultValue(1).range(1, 500).sliderRange(1, 50).build()
    );
    private final Setting<Boolean> autoDisable = sgFire.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgFire.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    private boolean wasPressed = false;

    public ManualClick() {
        super(AddonTemplate.DUPE_CATEGORY, "manual-click",
            "Hand-crafts a ClickSlotC2SPacket (syncId/revision/slot/button/action) and fires it. Bring-your-own-payload interaction tester.");
    }

    @Override
    public void onActivate() { wasPressed = false; }

    private SlotActionType resolveAction() {
        return switch (action.get()) {
            case PICKUP -> SlotActionType.PICKUP;
            case QUICK_MOVE -> SlotActionType.QUICK_MOVE;
            case SWAP -> SlotActionType.SWAP;
            case CLONE -> SlotActionType.CLONE;
            case THROW -> SlotActionType.THROW;
            case QUICK_CRAFT -> SlotActionType.QUICK_CRAFT;
            case PICKUP_ALL -> SlotActionType.PICKUP_ALL;
        };
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;

        boolean fire;
        if (trigger.get() == Trigger.EACH_TICK) {
            fire = true;
        } else {
            boolean pressed = key.get().isPressed();
            fire = pressed && !wasPressed; // rising edge
            wasPressed = pressed;
        }
        if (!fire) return;

        var handler = mc.player.currentScreenHandler;
        int syncId = syncMode.get() == SyncMode.CUSTOM
            ? customSyncId.get()
            : (handler == null ? 0 : handler.syncId);
        int revision = switch (revMode.get()) {
            case CURRENT -> handler == null ? 0 : handler.getRevision();
            case ZERO -> 0;
            case CUSTOM -> customRev.get();
        };

        for (int i = 0; i < count.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, revision,
                (short) (int) slot.get(), (byte) (int) button.get(),
                resolveAction(),
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
        info("Sent %dx click → syncId %d rev %d slot %d button %d %s",
            count.get(), syncId, revision, slot.get(), button.get(), action.get());
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
