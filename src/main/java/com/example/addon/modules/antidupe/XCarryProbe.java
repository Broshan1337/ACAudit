package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: X-Carry (item carry through ephemeral slots across a container close)
 *
 * <p>A container/inventory screen has slots that are NOT persistent storage: the 2x2 crafting
 * grid, the craft-result slot, the armor slots, the offhand slot, and the cursor. Vanilla returns
 * or drops the contents of these on close. Plugins that build custom GUIs on top of the inventory
 * or a chest menu — and Paper's virtual-inventory handling — sometimes forget to clear one of these
 * on every close path, so an item parked there can be carried into the next screen or duplicated.
 *
 * <p>The probe parks a stack in a chosen ephemeral slot and then sends
 * {@link CloseHandledScreenC2SPacket}. On a hardened server the item is atomically returned to the
 * player inventory (no gain, no loss). On a vulnerable one it persists in the ephemeral slot,
 * duplicates, or is silently voided — all findings.
 *
 * <p>Targets (player inventory menu, syncId 0): craft grid slot 1, craft result 0, armor 5-8,
 * offhand 45, or the CURSOR (leave the picked-up stack carried on close). Open your inventory (or
 * the plugin GUI) first. Patch signal: on EVERY close path (explicit, timeout, disconnect), force
 * all non-storage slots (grid, result, cursor, armor, offhand) back to the player inventory or drop
 * them atomically before releasing the session. Non-op, needs an item. Own server only.
 */
public class XCarryProbe extends Module {
    public enum Target { CURSOR, CRAFT_GRID, CRAFT_RESULT, ARMOR, OFFHAND }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Target> target = sgGeneral.add(new EnumSetting.Builder<Target>()
        .name("carry-slot").description("Which ephemeral slot to park the item in before closing.")
        .defaultValue(Target.CURSOR).build());
    private final Setting<Integer> sourceSlot = sgGeneral.add(new IntSetting.Builder()
        .name("source-slot").description("Handler slot holding the test item (hotbar slot 0 = 36).")
        .defaultValue(36).range(0, 200).sliderRange(0, 45).build());
    private final Setting<Boolean> closeAfter = sgGeneral.add(new BoolSetting.Builder()
        .name("close-after").description("Send the container-close packet after parking the item (the carry).")
        .defaultValue(true).build());
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Run one park+close cycle (works inside the GUI).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_X)).build());

    private boolean wasPressed = false;
    private int packetsSent = 0;

    public XCarryProbe() {
        super(AddonTemplate.DUPE_CATEGORY, "xcarry-probe",
            "Parks an item in an ephemeral slot (craft grid/result/armor/offhand/cursor) then closes the container, to test whether the server clears non-storage slots on close.");
    }

    @Override
    public void onActivate() { wasPressed = false; packetsSent = 0;
        info("Open your inventory / the plugin GUI, aim an item into 'source-slot', then press the key. Watch whether the item returns, dupes, or vanishes."); }

    private int targetSlotId() {
        return switch (target.get()) {
            case CURSOR -> -1;       // handled specially (leave carried)
            case CRAFT_GRID -> 1;    // player inventory craft grid slot 1
            case CRAFT_RESULT -> 0;
            case ARMOR -> 5;         // helmet
            case OFFHAND -> 45;
        };
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || !(mc.currentScreen instanceof HandledScreen<?>)) return;
        boolean pressed = key.get().isPressed();
        boolean fire = pressed && !wasPressed;
        wasPressed = pressed;
        if (!fire) return;

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId, rev = handler.getRevision();

        // Pick the source stack up into the cursor.
        send(syncId, rev, (short) (int) sourceSlot.get(), (byte) 0, SlotActionType.PICKUP);

        if (target.get() != Target.CURSOR) {
            // Place it into the ephemeral slot, leaving it there.
            send(syncId, handler.getRevision(), (short) targetSlotId(), (byte) 0, SlotActionType.PICKUP);
            info("Parked item from slot %d into %s (slot %d).", sourceSlot.get(), target.get(), targetSlotId());
        } else {
            info("Item picked up into the CURSOR — will be carried on close.");
        }

        if (closeAfter.get()) {
            mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(syncId));
            packetsSent++;
            info("Sent close with the item still in %s. Now re-open and check: did it come back (GOOD), dupe, or vanish?", target.get());
        }
    }

    private void send(int syncId, int rev, short slot, byte button, SlotActionType type) {
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, rev, slot, button, type, new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
    }

    @Override
    public void onDeactivate() { info("Summary: %d packets sent.", packetsSent); }
}
