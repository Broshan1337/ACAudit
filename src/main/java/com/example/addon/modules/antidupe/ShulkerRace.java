package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Shulker Race (open-vs-break)
 *
 * The classic shulker dupe: with a shulker GUI open, quick-move its contents
 * into your inventory AND break the shulker block in the same sequence. The
 * broken block drops a shulker item carrying its full NBT contents - if teardown
 * isn't atomic, those items now exist both in your inventory and in the dropped
 * shulker.
 *
 * Look at the shulker (crosshair stays on it while the GUI is open), then press
 * the key. Order is configurable so you can test grab-then-break and
 * break-then-grab.
 *
 * Patch signal: on block break/removal, SYNCHRONOUSLY close every container
 * backed by that block and reconcile the inventory before the block's drop is
 * created. The drop must reflect post-extraction contents, not the snapshot.
 */
public class ShulkerRace extends Module {
    public enum Order { GRAB_THEN_BREAK, BREAK_THEN_GRAB }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Look at the shulker (GUI open) and press to run the race.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_H)).build()
    );
    private final Setting<Order> order = sgGeneral.add(new EnumSetting.Builder<Order>()
        .name("order").description("Grab contents first or break the block first.")
        .defaultValue(Order.GRAB_THEN_BREAK).build()
    );
    private final Setting<Boolean> grab = sgGeneral.add(new BoolSetting.Builder()
        .name("grab-contents").description("Quick-move the shulker's slots into your inventory.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> breakBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("break-block").description("Send block-break packets on the shulker.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private boolean wasPressed = false;

    public ShulkerRace() {
        super(AddonTemplate.DUPE_CATEGORY, "shulker-race",
            "Quick-moves a shulker's contents and breaks the block in one sequence. Tests open-container vs block-removal atomicity.");
    }

    @Override
    public void onActivate() { wasPressed = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) {
            warning("Not looking at a block.");
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (!(mc.world.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock)) {
            warning("Crosshair block is not a shulker.");
            return;
        }
        Direction dir = hit.getSide();

        if (order.get() == Order.GRAB_THEN_BREAK) {
            if (grab.get()) grabContents();
            if (breakBlock.get()) breakAt(pos, dir);
        } else {
            if (breakBlock.get()) breakAt(pos, dir);
            if (grab.get()) grabContents();
        }
        info("Shulker race fired (%s) at %s", order.get(), pos.toShortString());
    }

    private void grabContents() {
        var handler = mc.player.currentScreenHandler;
        if (handler == null) return;
        int containerSlots = handler.slots.size() - 36; // player inv is always 36 trailing slots
        if (containerSlots <= 0) return;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        for (int i = 0; i < containerSlots; i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) i, (byte) 0, SlotActionType.QUICK_MOVE,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        }
    }

    private void breakAt(BlockPos pos, Direction dir) {
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, dir));
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, dir));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
