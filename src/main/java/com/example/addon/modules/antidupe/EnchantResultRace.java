package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.Disclosure;
import com.example.addon.audit.Severity;
import com.example.addon.modules.crash.PreStress;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Enchant Result Race (enchant-before-options-generate)
 *
 * The enchanting table computes its three enchantment options from the item currently in
 * the enchant slot. If a player changes the item and immediately clicks an enchant button
 * in the SAME tick — before the server recomputes and re-validates the options — the
 * server may apply the OLD options (or the old lapis/level cost) to the NEW item: a
 * cheaper or mismatched enchant, or an enchant applied to an item that should not be
 * enchantable.
 *
 * <p>This shift-clicks a fresh item from your inventory into the enchant slot and, in the
 * same tick, sends a {@link ButtonClickC2SPacket} selecting an enchant option — the exact
 * change-then-pick race. It grades the result via {@link DupeObserver} (item delta +
 * server messages / resyncs).
 *
 * <p>Distinct from station-result-race (anvil/grindstone OUTPUT-slot timing): this targets
 * the enchant-OPTION generation window specifically, which is button-driven, not slot-driven.
 *
 * <p>Patch signal: recompute and re-validate enchant options against the item's current
 * state at the moment the button is processed — never trust the options snapshot from a
 * previous tick; reject a button click whose option index no longer matches a freshly
 * computed option for the current item + level.
 *
 * <p>Severity if it succeeds: MEDIUM → HIGH (underpriced or illegitimate enchants; with a
 * value plugin, economic gain). Works for any player at an enchant table; no op.
 *
 * Put a spare item in a hotbar/inventory slot, open an enchant table, aim, press the key.
 * Run against your OWN server.
 */
public class EnchantResultRace extends Module {
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> swapFromSlot = sgGeneral.add(new IntSetting.Builder()
        .name("swap-from-slot").description("Inventory slot holding the item to shove into the enchant slot (changes the item mid-race).")
        .defaultValue(9).range(0, 200).sliderRange(0, 45).build());
    private final Setting<Integer> enchantButton = sgGeneral.add(new IntSetting.Builder()
        .name("enchant-button").description("Which enchant option to pick (0 = top, 1 = middle, 2 = bottom).")
        .defaultValue(0).range(0, 2).sliderRange(0, 2).build());
    private final Setting<Boolean> pickBeforeSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("pick-before-swap").description("Send the enchant button BEFORE the item swap in the same tick (tests the opposite ordering).")
        .defaultValue(false).build());
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build());
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one swap+enchant race (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_G))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build());
    private final Setting<Integer> attempts = sgGeneral.add(new IntSetting.Builder()
        .name("attempts").description("Race attempts per fire.").defaultValue(1).range(1, 50).sliderRange(1, 10).build());

    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.").defaultValue(true).build());
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.").defaultValue(true).build());

    private int ticksActive = 0, packetsSent = 0;
    private boolean wasPressed = false;

    public static final Disclosure DISCLOSURE = new Disclosure(
        "custom-enchant / enchant-GUI plugins that cache the three enchant options and trust the option index at button-click time",
        "options are computed from the item in one tick but the button click is validated against that stale snapshot, not the current item",
        "recompute + re-validate enchant options against the item's current state when the button is processed; reject an option index that no longer matches",
        Severity.MEDIUM, "any player at an enchant table, no op");

    public EnchantResultRace() {
        super(AddonTemplate.DUPE_CATEGORY, "enchant-result-race",
            "Swaps the item in an enchant table and clicks an enchant button in the same tick. Tests whether enchant options are re-validated against the current item.");
    }

    @Override
    public void onActivate() {
        ticksActive = packetsSent = 0; wasPressed = false; obs.onActivate(); preStress.onActivate(this);
        for (String l : DISCLOSURE.lines()) info("%s", l);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!GuiState.containerOpen(mc)) return;
        ticksActive++;
        obs.tick();

        boolean fire;
        if (trigger.get() == Trigger.EACH_TICK) fire = true;
        else { boolean p = key.get().isPressed(); fire = p && !wasPressed; wasPressed = p; }
        if (!fire) return;

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();

        for (int i = 0; i < attempts.get(); i++) {
            if (pickBeforeSwap.get()) {
                mc.player.networkHandler.sendPacket(new ButtonClickC2SPacket(syncId, enchantButton.get()));
                packetsSent++;
                sendSwap(syncId, rev);
            } else {
                sendSwap(syncId, rev);
                mc.player.networkHandler.sendPacket(new ButtonClickC2SPacket(syncId, enchantButton.get()));
                packetsSent++;
            }
        }
        obs.markFired();
        info("Enchant race: swap slot %d ↔ table, button %d (%s).", swapFromSlot.get(), enchantButton.get(),
            pickBeforeSwap.get() ? "pick-then-swap" : "swap-then-pick");
    }

    private void sendSwap(int syncId, int rev) {
        // Shift-move the item from an inventory slot into the enchant slot, changing the item under the options.
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, rev, (short) (int) swapFromSlot.get(), (byte) 0, SlotActionType.QUICK_MOVE,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.survey(event.packet, l -> info("%s", l)); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
