package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.Disclosure;
import com.example.addon.audit.Severity;
import com.example.addon.modules.crash.PreStress;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Crate / Loot-Box Result Race (grab-before-animation-completes)
 *
 * Crate and loot-box plugins open a GUI, play a spinning ANIMATION over several ticks,
 * and only place the awarded item in the result slot when the animation "finishes". If
 * the plugin renders the reward item into the slot at animation START (or reuses a
 * static preview slot) and validates/locks it only at animation END, a player can click
 * the result slot DURING the animation and pull the reward before the server-side
 * award/deduct logic runs — receiving it without spending the key, or receiving several.
 *
 * <p>On any GUI open this immediately spams PICKUP + QUICK_MOVE on the configured result
 * slot for the whole animation window, then grades the item delta ({@link DupeObserver}).
 * An item that leaves the crate GUI into your inventory before the crate's payout message
 * is a confirmed animation-window race.
 *
 * <p>Patch signal: never place the real reward item in an interactable slot until the
 * animation and the atomic key-deduct + award have committed; make the result slot
 * non-takeable (cancel InventoryClickEvent) until then; drive rewards server-side by
 * giving to the player's inventory, not by leaving a takeable item in the menu.
 *
 * <p>Severity if it succeeds: HIGH → CRITICAL (free crate rewards without spending keys;
 * economy/item inflation). Works for any player who can open the crate; no op.
 *
 * Enable, then open the crate. Runs automatically on open. Run against your OWN server.
 */
public class CrateResultRace extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> resultSlot = sgGeneral.add(new IntSetting.Builder()
        .name("result-slot").description("The crate's reward/result slot (use slot-overlay to find it — often the centre).")
        .defaultValue(13).range(0, 200).sliderRange(0, 53).build());
    private final Setting<Boolean> allSlots = sgGeneral.add(new BoolSetting.Builder()
        .name("all-container-slots").description("Ignore result-slot and hammer every container slot (find the takeable one).")
        .defaultValue(false).build());
    private final Setting<Integer> animationTicks = sgGeneral.add(new IntSetting.Builder()
        .name("animation-window-ticks").description("How long to keep grabbing after open (cover the whole spin animation).")
        .defaultValue(40).range(1, 200).sliderRange(5, 100).build());
    private final Setting<Integer> clicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick").defaultValue(4).range(1, 100).sliderRange(1, 30).build());

    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.").defaultValue(true).build());
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.").defaultValue(true).build());

    private int ticksActive = 0, packetsSent = 0;
    private int window = 0;

    public static final Disclosure DISCLOSURE = new Disclosure(
        "crate / loot-box plugins that show a spin animation and place a takeable reward item in a menu slot",
        "the real reward is placed in an interactable slot before the animation + atomic key-deduct/award commit",
        "keep the result slot non-takeable (cancel InventoryClickEvent) until the award commits; give rewards to the inventory server-side, not via a takeable menu item",
        Severity.HIGH, "any player who can open the crate, no op");

    public CrateResultRace() {
        super(AddonTemplate.DUPE_CATEGORY, "crate-result-race",
            "On crate GUI open, grabs the result slot throughout the spin animation. Tests grab-before-animation-validation. Fully automated.");
    }

    @Override
    public void onActivate() {
        ticksActive = packetsSent = 0; window = 0; obs.onActivate(); preStress.onActivate(this);
        for (String l : DISCLOSURE.lines()) info("%s", l);
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (event.screen instanceof HandledScreen) window = animationTicks.get();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        obs.tick();
        if (window <= 0) return;
        if (!GuiState.containerOpen(mc)) { window = 0; return; }

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        int lo, hi;
        if (allSlots.get()) { lo = 0; hi = Math.max(0, handler.slots.size() - 36); }
        else { lo = resultSlot.get(); hi = resultSlot.get() + 1; }

        for (int slot = lo; slot < hi && slot < handler.slots.size(); slot++) {
            for (int c = 0; c < clicksPerTick.get(); c++) {
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) slot, (byte) 0, SlotActionType.PICKUP,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) slot, (byte) 0, SlotActionType.QUICK_MOVE,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
                packetsSent += 2;
            }
        }
        obs.markFired();
        window--;
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
