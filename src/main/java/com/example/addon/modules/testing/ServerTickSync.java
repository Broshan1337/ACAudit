package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.antidupe.GuiState;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Server-Tick Sync (precise burst delivery for tick-boundary and revision races)
 *
 * <p>The reason the well-known dupe clients are effective is not exotic packets — it is TIMING.
 * A burst of ordinary clicks/commands that lands on the exact server tick a container restocks, a
 * transaction commits, or a revision advances wins races that the same packets sent a few ms off
 * would lose. This module delivers a pre-built burst under two precise triggers, so our race and
 * dupe modules have a measurable "did precise delivery change the outcome?" control.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code REVISION_TARGET} — stamp a burst of clicks with a PREDICTED-AHEAD container revision
 *       ({@code current + revision-offset}) and fire them together. Tests whether the server accepts
 *       clicks claiming a future state id (the "pre-generate at a target revision" delivery the dupe
 *       clients use). Deterministic; needs a container open.</li>
 *   <li>{@code TICK_ALIGN} — fire the burst aligned to the estimated server-tick boundary plus an
 *       offset, optionally compensated by half your ping. <b>Honest limitation:</b> without a
 *       server-authoritative tick signal this alignment is a wall-clock ESTIMATE (~50 ms period),
 *       the same class of estimate those clients use — treat it as "closer than unaligned," not
 *       frame-perfect.</li>
 * </ul>
 *
 * <p>Payload is a click burst on a slot or a repeated command. Complements {@code revision-abuse}
 * (which probes revision FIELD values) by testing DELIVERY precision. Patch signal: make the
 * critical section (restock, commit, slot-state advance) tick-atomic and validate the revision
 * against the live container so a future-stamped or tick-aligned burst cannot slip an extra action
 * in. Non-op, client-side. Own server only.
 */
public class ServerTickSync extends Module {
    public enum Mode { REVISION_TARGET, TICK_ALIGN }
    public enum Payload { CLICK, COMMAND }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Fire at a predicted container revision, or aligned to the estimated server tick.")
        .defaultValue(Mode.REVISION_TARGET).build());
    private final Setting<Payload> payload = sgGeneral.add(new EnumSetting.Builder<Payload>()
        .name("payload").description("What the burst is: slot clicks or a repeated command.")
        .defaultValue(Payload.CLICK).build());
    private final Setting<Integer> burst = sgGeneral.add(new IntSetting.Builder()
        .name("burst").description("Packets in the burst.")
        .defaultValue(8).range(1, 200).sliderRange(1, 40).build());
    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("CLICK payload: slot to PICKUP.")
        .defaultValue(0).range(0, 200).sliderRange(0, 53)
        .visible(() -> payload.get() == Payload.CLICK).build());
    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("COMMAND payload: command to repeat (no leading slash).")
        .defaultValue("sell all").visible(() -> payload.get() == Payload.COMMAND).build());
    private final Setting<Integer> revisionOffset = sgGeneral.add(new IntSetting.Builder()
        .name("revision-offset").description("REVISION_TARGET: how far ahead of the current revision to stamp the burst.")
        .defaultValue(1).range(-8, 64).sliderRange(0, 8)
        .visible(() -> mode.get() == Mode.REVISION_TARGET).build());
    private final Setting<Integer> tickOffsetMs = sgGeneral.add(new IntSetting.Builder()
        .name("tick-offset-ms").description("TICK_ALIGN: ms into the ~50ms server tick to fire at.")
        .defaultValue(2).range(0, 49).sliderRange(0, 49)
        .visible(() -> mode.get() == Mode.TICK_ALIGN).build());
    private final Setting<Boolean> pingCompensate = sgGeneral.add(new BoolSetting.Builder()
        .name("ping-compensate").description("TICK_ALIGN: fire half your ping early so the burst ARRIVES on the boundary.")
        .defaultValue(true).visible(() -> mode.get() == Mode.TICK_ALIGN).build());
    private final Setting<Boolean> repeat = sgGeneral.add(new BoolSetting.Builder()
        .name("repeat").description("Keep firing (each alignment / each key hold). Off = one-shot per key press.")
        .defaultValue(false).build());
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Arm/fire the burst.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_G)).build());

    private boolean wasPressed = false;
    private long anchorMs = 0;
    private int lastFiredCycle = -1, fires = 0, packetsSent = 0;

    public ServerTickSync() {
        super(AddonTemplate.TESTING_CATEGORY, "server-tick-sync",
            "Delivers a click/command burst at a predicted container revision, or aligned to the estimated server tick, for winning tick-boundary and revision races.");
    }

    @Override
    public void onActivate() {
        wasPressed = false; anchorMs = System.currentTimeMillis(); lastFiredCycle = -1; fires = 0; packetsSent = 0;
        info("Mode %s. %s", mode.get(), mode.get() == Mode.TICK_ALIGN
            ? "TICK_ALIGN is a wall-clock estimate — not frame-perfect." : "REVISION_TARGET stamps the burst at current-rev + offset.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        boolean pressed = key.get().isPressed();
        boolean edge = pressed && !wasPressed;
        wasPressed = pressed;

        if (mode.get() == Mode.REVISION_TARGET) {
            if (edge || (repeat.get() && pressed)) fireRevision();
        } else {
            // Only while armed (key held in repeat, or a single edge that fires on the next boundary).
            if (!pressed && !edge) return;
            long into = (System.currentTimeMillis() - anchorMs) % 50L;
            int cycle = (int) ((System.currentTimeMillis() - anchorMs) / 50L);
            long ping = pingMs();
            long targetInto = tickOffsetMs.get();
            long fireInto = pingCompensate.get() ? (targetInto - ping / 2 + 50) % 50 : targetInto;
            if (cycle != lastFiredCycle && into >= fireInto && into < fireInto + 8) {
                lastFiredCycle = cycle;
                fireBurst();
                if (!repeat.get()) wasPressed = true; // consume the press
            }
        }
    }

    private void fireRevision() {
        if (payload.get() == Payload.CLICK && !GuiState.containerOpen(mc)) { info("Open a container for REVISION_TARGET clicks."); return; }
        int syncId = mc.player.currentScreenHandler.syncId;
        int rev = mc.player.currentScreenHandler.getRevision() + revisionOffset.get();
        info("Firing revision-stamped burst: %d packets at rev %d (current %d).", burst.get(), rev, mc.player.currentScreenHandler.getRevision());
        for (int i = 0; i < burst.get(); i++) sendClick(syncId, rev);
        fires++;
    }

    private void fireBurst() {
        fires++;
        if (payload.get() == Payload.COMMAND) {
            for (int i = 0; i < burst.get(); i++) { mc.player.networkHandler.sendChatCommand(command.get().trim()); packetsSent++; }
        } else {
            if (!GuiState.containerOpen(mc)) return;
            int syncId = mc.player.currentScreenHandler.syncId;
            int rev = mc.player.currentScreenHandler.getRevision();
            for (int i = 0; i < burst.get(); i++) sendClick(syncId, rev);
        }
    }

    private void sendClick(int syncId, int rev) {
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, rev, (short) (int) slot.get(), (byte) 0, SlotActionType.PICKUP,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
    }

    private long pingMs() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0;
        var e = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return e == null ? 0 : Math.max(0, e.getLatency());
    }

    @Override
    public void onDeactivate() { info("Summary: %d bursts, %d packets.", fires, packetsSent); }
}
