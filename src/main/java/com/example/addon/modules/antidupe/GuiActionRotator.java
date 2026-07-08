package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.Disclosure;
import com.example.addon.audit.ResponseTimer;
import com.example.addon.audit.Severity;
import com.example.addon.modules.crash.PreStress;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.HandledScreenAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: GUI Action Rotator (action-type coverage + latency)
 *
 * Plugin GUI handlers are written and tested against the ONE click type the menu is
 * "supposed" to receive (usually a left click). This module fires a configurable set
 * of the OTHER action types at a single slot — LEFT, RIGHT, SHIFT-LEFT, SHIFT-RIGHT,
 * number-key SWAP (hotbar 0–8), middle-click CLONE, double-click PICKUP_ALL, THROW,
 * and the outside-inventory click (slot −999) — either ALL IN ONE TICK (the "does the
 * handler process an action type it never expected" test) or ROTATING one per fire.
 *
 * <p>It answers three of the underexplored GUI questions at once:
 *   • does the plugin handle every action type, or only the one it expects?
 *   • does a number-key hotbar swap / creative clone bypass a shop's "buy" handler?
 *   • does an outside (−999) click get treated as a drop, an ignore, or an exploit?
 *
 * <p>A CLICK-COUNT SWEEP escalates clicks-per-action across the run (1→max) and logs
 * the rate at which the server's response behaviour changes — so you can locate a
 * rate-limit threshold instead of guessing. Every fire is timed to the next server
 * response ({@link ResponseTimer}) so the report carries real per-run latency, and
 * pairing with PreStress shows whether that latency degrades under load.
 *
 * <p>Patch signal: a plugin GUI must whitelist the exact (slot, action) pairs it
 * expects and reject everything else — never fall through to a default handler for an
 * unexpected InventoryAction; treat SWAP/CLONE/outside clicks in a menu as invalid;
 * rate-limit clicks per slot per tick to 1.
 *
 * <p>Severity if it succeeds: HIGH → CRITICAL (unexpected-action-type dupes and
 * shop-bypass buys are money/item creation). Works for any player, no op, no items.
 *
 * Point at the button in an open plugin GUI and enable. Run against your OWN server.
 */
public class GuiActionRotator extends Module {
    public enum Target { HOVERED, FIXED }
    public enum FireMode { ALL_IN_TICK, ROTATE }

    private final SettingGroup sgTarget = settings.getDefaultGroup();
    private final SettingGroup sgActions = settings.createGroup("Action types");
    private final SettingGroup sgFire = settings.createGroup("Fire");

    // --- targeting ---
    private final Setting<Target> target = sgTarget.add(new EnumSetting.Builder<Target>()
        .name("target").description("HOVERED = the slot under your cursor; FIXED = a slot id you set.")
        .defaultValue(Target.HOVERED).build());
    private final Setting<Integer> fixedSlot = sgTarget.add(new IntSetting.Builder()
        .name("fixed-slot").description("Slot id when target is FIXED.")
        .defaultValue(0).range(0, 200).sliderRange(0, 53)
        .visible(() -> target.get() == Target.FIXED).build());
    private final Setting<Integer> swapButton = sgTarget.add(new IntSetting.Builder()
        .name("swap-hotbar-button").description("Hotbar index (0–8) used for the number-key SWAP action.")
        .defaultValue(0).range(0, 8).sliderRange(0, 8).build());

    // --- which action types to include ---
    private final Setting<Boolean> aLeft = sgActions.add(new BoolSetting.Builder().name("left").defaultValue(true).build());
    private final Setting<Boolean> aRight = sgActions.add(new BoolSetting.Builder().name("right").defaultValue(true).build());
    private final Setting<Boolean> aShiftLeft = sgActions.add(new BoolSetting.Builder().name("shift-left").defaultValue(true).build());
    private final Setting<Boolean> aShiftRight = sgActions.add(new BoolSetting.Builder().name("shift-right").defaultValue(false).build());
    private final Setting<Boolean> aSwap = sgActions.add(new BoolSetting.Builder().name("number-key-swap").defaultValue(true).build());
    private final Setting<Boolean> aClone = sgActions.add(new BoolSetting.Builder().name("middle-clone").defaultValue(true).build());
    private final Setting<Boolean> aDouble = sgActions.add(new BoolSetting.Builder().name("double-click").defaultValue(true).build());
    private final Setting<Boolean> aThrow = sgActions.add(new BoolSetting.Builder().name("throw").defaultValue(false).build());
    private final Setting<Boolean> aOutside = sgActions.add(new BoolSetting.Builder()
        .name("outside-click").description("The vanilla outside-inventory click (slot −999).").defaultValue(true).build());

    // --- firing ---
    private final Setting<FireMode> fireMode = sgFire.add(new EnumSetting.Builder<FireMode>()
        .name("fire-mode").description("ALL_IN_TICK = send every included action in one tick; ROTATE = one action per tick.")
        .defaultValue(FireMode.ALL_IN_TICK).build());
    private final Setting<Integer> clicksPerAction = sgFire.add(new IntSetting.Builder()
        .name("clicks-per-action").description("Copies of each included action sent per tick.")
        .defaultValue(5).range(1, 500).sliderRange(1, 100).build());
    private final Setting<Boolean> sweep = sgFire.add(new BoolSetting.Builder()
        .name("clicks-sweep").description("Escalate clicks-per-action from 1 up to the value above over the run, logging where the server's response rate changes (rate-limit threshold finder).")
        .defaultValue(false).build());
    private final Setting<Integer> sweepStepTicks = sgFire.add(new IntSetting.Builder()
        .name("sweep-step-ticks").description("Ticks spent at each sweep level.")
        .defaultValue(20).range(2, 200).sliderRange(5, 60).visible(sweep::get).build());
    private final Setting<Boolean> logHovered = sgFire.add(new BoolSetting.Builder()
        .name("log-target-slot").description("Print the targeted slot id when it changes.")
        .defaultValue(true).build());

    private final PreStress preStress = new PreStress(sgFire);
    private final DupeObserver obs = new DupeObserver(sgFire);
    private final ResponseTimer timer = new ResponseTimer();

    private final Setting<Boolean> autoDisable = sgFire.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.").defaultValue(true).build());
    private final Setting<Boolean> showStats = sgFire.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count + latency on deactivate.").defaultValue(true).build());

    private int ticksActive = 0, packetsSent = 0;
    private int lastLoggedSlot = Integer.MIN_VALUE;
    private int rotIdx = 0;
    private int sweepLevel = 1, sweepTick = 0, lastResponseCount = 0;

    public static final Disclosure DISCLOSURE = new Disclosure(
        "shop/kit/menu plugins whose InventoryClickEvent handler assumes one click type (usually left) and falls through on others",
        "no whitelist of expected (slot, action) pairs — SWAP/CLONE/PICKUP_ALL/outside clicks reach a default handler or the buy logic",
        "whitelist exact (slot, action) pairs per menu; reject SWAP/CLONE/outside clicks; rate-limit clicks per slot per tick to 1",
        Severity.HIGH, "any player, no op, no items");

    public GuiActionRotator() {
        super(AddonTemplate.DUPE_CATEGORY, "gui-action-rotator",
            "Fires every GUI action type (left/right/shift/swap/clone/double/throw/outside) at one slot, same-tick or rotating, with a click sweep and response-latency measurement.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; lastLoggedSlot = Integer.MIN_VALUE; rotIdx = 0;
        sweepLevel = 1; sweepTick = 0; lastResponseCount = 0;
        obs.onActivate(); timer.reset(); preStress.onActivate(this);
        for (String l : DISCLOSURE.lines()) info("%s", l);
    }

    /** One (action, button, slotOverride) the module can fire. slotOverride = Integer.MIN_VALUE means "use target slot". */
    private record Act(String name, SlotActionType action, int button, int slotOverride) {}

    private List<Act> enabledActs(int targetSlot) {
        List<Act> acts = new ArrayList<>();
        if (aLeft.get())       acts.add(new Act("left",        SlotActionType.PICKUP,     0, Integer.MIN_VALUE));
        if (aRight.get())      acts.add(new Act("right",       SlotActionType.PICKUP,     1, Integer.MIN_VALUE));
        if (aShiftLeft.get())  acts.add(new Act("shift-left",  SlotActionType.QUICK_MOVE, 0, Integer.MIN_VALUE));
        if (aShiftRight.get()) acts.add(new Act("shift-right", SlotActionType.QUICK_MOVE, 1, Integer.MIN_VALUE));
        if (aSwap.get())       acts.add(new Act("swap",        SlotActionType.SWAP, swapButton.get(), Integer.MIN_VALUE));
        if (aClone.get())      acts.add(new Act("clone",       SlotActionType.CLONE,      2, Integer.MIN_VALUE));
        if (aDouble.get())     acts.add(new Act("double",      SlotActionType.PICKUP_ALL, 0, Integer.MIN_VALUE));
        if (aThrow.get())      acts.add(new Act("throw",       SlotActionType.THROW,      0, Integer.MIN_VALUE));
        if (aOutside.get())    acts.add(new Act("outside",     SlotActionType.PICKUP,     0, -999));
        return acts;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!GuiState.containerOpen(mc)) return;
        ticksActive++;
        obs.tick();
        timer.tickTimeout(1000);

        var handler = mc.player.currentScreenHandler;
        int targetSlot;
        if (target.get() == Target.HOVERED) {
            if (!(mc.currentScreen instanceof HandledScreen<?> screen)) return;
            Slot focused = ((HandledScreenAccessor) screen).meteor$getFocusedSlot();
            if (focused == null) return;
            targetSlot = focused.id;
        } else {
            targetSlot = fixedSlot.get();
        }

        if (logHovered.get() && targetSlot != lastLoggedSlot) {
            info("Targeting slot %d (%s, x%d/action)", targetSlot, fireMode.get(), effectiveClicks());
            lastLoggedSlot = targetSlot;
        }

        List<Act> acts = enabledActs(targetSlot);
        if (acts.isEmpty()) return;

        int syncId = handler.syncId;
        int rev = handler.getRevision();
        int per = effectiveClicks();

        if (fireMode.get() == FireMode.ALL_IN_TICK) {
            for (Act a : acts) fire(a, targetSlot, syncId, rev, per);
        } else {
            Act a = acts.get(rotIdx % acts.size());
            rotIdx++;
            fire(a, targetSlot, syncId, rev, per);
        }
        obs.markFired();
        timer.markFire();
        advanceSweep();
    }

    private void fire(Act a, int targetSlot, int syncId, int rev, int per) {
        int slot = a.slotOverride() == Integer.MIN_VALUE ? targetSlot : a.slotOverride();
        for (int i = 0; i < per; i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) slot, (byte) a.button(), a.action(),
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
    }

    private int effectiveClicks() { return sweep.get() ? sweepLevel : clicksPerAction.get(); }

    private void advanceSweep() {
        if (!sweep.get()) return;
        sweepTick++;
        if (sweepTick < sweepStepTicks.get()) return;
        sweepTick = 0;
        // Report matched-response count at the level we just finished before stepping up.
        int delta = timer.count() - lastResponseCount;
        info("Sweep level %d/action: %d matched responses in the last %d ticks.",
            sweepLevel, delta, sweepStepTicks.get());
        lastResponseCount = timer.count();
        if (sweepLevel < clicksPerAction.get()) {
            sweepLevel = Math.min(clicksPerAction.get(), Math.max(sweepLevel + 1, sweepLevel * 2));
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            for (String l : timer.reportLines("click→response")) info("%s", l);
            obs.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.survey(event.packet, l -> info("%s", l));
        if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket
            || event.packet instanceof InventoryS2CPacket
            || event.packet instanceof GameMessageS2CPacket) {
            timer.markResponse();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
