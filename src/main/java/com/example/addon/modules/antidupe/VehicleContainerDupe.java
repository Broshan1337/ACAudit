package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.vehicle.VehicleInventory;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.text.Text;

/**
 * AUDIT: Vehicle-Container Dupe (chest boat / minecart-with-chest persistence race)
 *
 * <p>Chest boats, chest rafts and storage minecarts hold their inventory ON THE ENTITY, saved with
 * the entity rather than with the player profile. That split is the vulnerability: pull an item out
 * of the vehicle container and then force an abrupt disconnect/kick before the two saves reconcile,
 * and the item can land in the player inventory while the entity is rolled back to still holding it
 * — a duplicate. Protection plugins that guard player inventories often ignore vehicle containers
 * entirely.
 *
 * <p>Flow: with the vehicle's container open, QUICK_MOVE (or THROW) an item out of it, then force a
 * disconnect a configurable number of ticks later. A CLEAN quit and an INVALID_SLOT kick persist on
 * different paths — try both. On relog, compare the vehicle contents + your inventory against what
 * should have moved once.
 *
 * <p>Patch signal: make vehicle-container mutations transactional with the player action and the
 * entity save; on disconnect mid-transfer, the item is fully in one place or the other, never both;
 * apply the same protection/anti-dupe guards to vehicle inventories as to player/chest inventories.
 * Requires a chest boat / minecart-with-chest container open. Non-op (needs the vehicle). Own server.
 */
public class VehicleContainerDupe extends Module {
    public enum Pull { QUICK_MOVE, THROW }
    public enum DisconnectMethod { CLEAN_QUIT, INVALID_SLOT_KICK }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Slot in the vehicle container to pull the item from (0 = first).")
        .defaultValue(0).range(0, 53).sliderRange(0, 26).build());
    private final Setting<Pull> pull = sgGeneral.add(new EnumSetting.Builder<Pull>()
        .name("pull-action").description("QUICK_MOVE = shift into your inventory; THROW = drop it.")
        .defaultValue(Pull.QUICK_MOVE).build());
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("disconnect-delay-ticks").description("Ticks between the pull and the disconnect. 0 = same tick.")
        .defaultValue(0).range(0, 40).sliderRange(0, 20).build());
    private final Setting<DisconnectMethod> method = sgGeneral.add(new EnumSetting.Builder<DisconnectMethod>()
        .name("disconnect-method").description("Clean quit vs. an invalid-slot kick — different save paths.")
        .defaultValue(DisconnectMethod.CLEAN_QUIT).build());
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Pull the item and disconnect (works inside the GUI).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_R)).build());

    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private boolean wasPressed = false;
    private int countdown = -1, packetsSent = 0;

    public VehicleContainerDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "vehicle-container-dupe",
            "Pulls an item from a chest-boat/minecart-chest container then force-disconnects, racing the entity-vs-player save split. Tests vehicle-inventory dupe protection.");
    }

    @Override
    public void onActivate() { wasPressed = false; countdown = -1; packetsSent = 0; obs.onActivate(); preStress.onActivate(this);
        info("Open a chest boat / minecart-with-chest container, then press the key."); }

    private boolean ridingVehicleContainer() {
        return mc.player != null && mc.player.getVehicle() instanceof VehicleInventory;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        obs.tick();

        if (countdown > 0) { if (--countdown == 0) { doDisconnect(); countdown = -1; } return; }

        boolean pressed = key.get().isPressed();
        boolean fire = pressed && !wasPressed;
        wasPressed = pressed;
        if (!fire) return;

        if (!GuiState.containerOpen(mc)) { warning("Open the vehicle's container first."); return; }
        if (!ridingVehicleContainer())
            info("Note: you don't appear to be riding a chest vehicle — make sure the OPEN container is the chest boat/minecart.");

        var handler = mc.player.currentScreenHandler;
        SlotActionType type = pull.get() == Pull.THROW ? SlotActionType.THROW : SlotActionType.QUICK_MOVE;
        byte button = pull.get() == Pull.THROW ? (byte) 1 : (byte) 0;
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            handler.syncId, handler.getRevision(), (short) (int) slot.get(), button, type,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
        obs.markFired();
        info("Pulled slot %d (%s). Disconnecting in %d ticks (%s).", slot.get(), pull.get(), delay.get(), method.get());

        if (delay.get() <= 0) doDisconnect();
        else countdown = delay.get();
    }

    private void doDisconnect() {
        if (mc.player == null) return;
        if (method.get() == DisconnectMethod.INVALID_SLOT_KICK) {
            warning("SetCarriedItem(-1) to force a kick.");
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(-1));
            packetsSent++;
        } else {
            warning("Clean disconnect (vehicle-container-dupe).");
            mc.player.networkHandler.getConnection().disconnect(Text.literal("ACAudit: vehicle-container-dupe test"));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) { obs.onKick(); if (isActive()) toggle(); }

    @Override
    public void onDeactivate() { info("Summary: %d packets sent.", packetsSent); obs.report(l -> info("%s", l)); preStress.onDeactivate(); }
}
