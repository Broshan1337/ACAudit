package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Station Result-Slot Race (axis 1 timing + axis 7 new stations)
 *
 * Every "work station" GUI has a virtual RESULT slot whose contents are computed
 * from the inputs and only materialise for an instant when a recipe/trade/smelt
 * completes. A plugin that protects the obvious chest-shop slot often forgets
 * these output slots entirely. This module SNIPEs a single output-grab at a
 * precise tick (or SWEEPs the offset to find the exploitable one) instead of
 * flooding — exactly the TOCTOU window between "result available" and "inputs
 * consumed".
 *
 * Stations (result slot in parentheses; some also need a selection packet first):
 *   FURNACE (2)        — take output at the tick a smelt finishes
 *   BREWING (0,1,2)    — take a potion at the tick brewing completes
 *   GRINDSTONE (2)     — take result at the tick an input is placed
 *   ANVIL (2)          — take repaired/renamed result vs. XP+input consumption
 *   LOOM (3)           — select pattern (button) then grab the result same-tick
 *   STONECUTTER (1)    — select recipe (button) then grab the result same-tick
 *   ENCHANT_TABLE (0)  — pick an option (button 0..2) then grab same-tick
 *   VILLAGER (2)       — select trade then grab the output at a restock tick
 *
 * What a vulnerable server does: hands out the output on the racing click while
 * still treating inputs/XP/trade-uses as not-yet-consumed -> dupe.
 * What a hardened server does: take-result validates the recipe/trade and
 * consumes inputs+cost under one lock before the next click is processed.
 * Fix: make result extraction atomic with input consumption; recompute the
 * output from authoritative input state at commit, never from a stale snapshot.
 *
 * Open the station GUI (set up inputs), enable. Run against your OWN server only.
 */
public class StationResultRace extends Module {
    public enum Station { FURNACE, BREWING, GRINDSTONE, ANVIL, LOOM, STONECUTTER, ENCHANT_TABLE, VILLAGER }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Station> station = sgGeneral.add(new EnumSetting.Builder<Station>()
        .name("station").description("Which work-station result slot to race.")
        .defaultValue(Station.FURNACE).build()
    );
    private final Setting<Integer> selection = sgGeneral.add(new IntSetting.Builder()
        .name("selection-index")
        .description("Button/trade index sent before the grab (LOOM/STONECUTTER/ENCHANT_TABLE button, VILLAGER trade id).")
        .defaultValue(0).range(0, 63).sliderRange(0, 12)
        .visible(() -> needsSelection(station.get())).build()
    );

    private final TickWindow window = new TickWindow(sgGeneral);
    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public StationResultRace() {
        super(AddonTemplate.DUPE_CATEGORY, "station-result-race",
            "Snipes a single output-grab on a work-station result slot (furnace/brewing/grindstone/anvil/loom/stonecutter/enchant/villager). Tests result-vs-input atomicity.");
    }

    private static boolean needsSelection(Station s) {
        return s == Station.LOOM || s == Station.STONECUTTER || s == Station.ENCHANT_TABLE || s == Station.VILLAGER;
    }

    private int resultSlot(Station s) {
        return switch (s) {
            case FURNACE, GRINDSTONE, ANVIL, VILLAGER -> 2;
            case BREWING, ENCHANT_TABLE -> 0;
            case LOOM -> 3;
            case STONECUTTER -> 1;
        };
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        window.onActivate(); obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null
            || mc.player.currentScreenHandler == mc.player.playerScreenHandler) return;
        ticksActive++;
        obs.tick();

        // Re-arm continuously so the window walks (SWEEP) or repeats (SNIPE).
        if (!window.armed()) window.arm();
        if (!window.shouldFire()) return;

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();

        // Selection packet first (race selection -> grab in the same tick).
        if (needsSelection(station.get())) {
            if (station.get() == Station.VILLAGER)
                mc.player.networkHandler.sendPacket(new SelectMerchantTradeC2SPacket(selection.get()));
            else
                mc.player.networkHandler.sendPacket(new ButtonClickC2SPacket(syncId, selection.get()));
            packetsSent++;
        }

        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, rev, (short) resultSlot(station.get()), (byte) 0, SlotActionType.QUICK_MOVE,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
        obs.markFired();
        if (window.mode() == TickWindow.Mode.SWEEP)
            info("Sniped %s result (slot %d) at offset %d.", station.get(), resultSlot(station.get()), window.lastFiredOffset());
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
