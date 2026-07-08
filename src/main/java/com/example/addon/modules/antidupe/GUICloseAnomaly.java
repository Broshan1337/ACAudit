package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;

/**
 * AUDIT: GUI Close Anomaly (abnormal close paths → dangling server-side session)
 *
 * <p>Most shop/economy/crate plugins wire their cleanup to the happy-path close packet. But a
 * client can leave a GUI in many abnormal ways, and each leaves a different server-side surface:
 * a pending transaction, a held lock, an attached listener, or a container session that never
 * gets torn down. The patch signal for ALL of these is the same: plugins must clean up session
 * state on disconnect, on timeout, and on ANY abnormal close — not only on the explicit close.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code SILENT_CLOSE} — press the module's own <b>close-key</b> to shut the GUI CLIENT-SIDE
 *       with NO {@link CloseHandledScreenC2SPacket}, leaving the server believing it's still open.
 *       This is the "packet close" a real modified client does. The key is a dedicated
 *       {@link KeybindSetting} because Meteor's module-toggle bind is swallowed while a screen is
 *       focused — this one is polled from the tick loop (raw key state), so it fires inside a GUI.</li>
 *   <li>{@code DOUBLE_CLOSE} — send the close packet twice for one syncId; tests double-cleanup /
 *       double-rollback.</li>
 *   <li>{@code WRONG_SYNCID} — send a close for a syncId that is NOT open while a real GUI is
 *       open; does the real GUI's close handler fire incorrectly, or server state desync?</li>
 *   <li>{@code DURING_TRANSACTION} — fire an economy command and the close packet in the SAME
 *       tick, repeatedly, to interrupt a transaction mid-commit (item/balance handling on abort).</li>
 *   <li>{@code CLOSE_REOPEN} — close, then re-open (crosshair block interact) in the same tick;
 *       does the new session inherit uncleared state, and does the syncId advance correctly?</li>
 * </ul>
 *
 * <p>(Disconnect-close simulation is intentionally omitted — a client cannot signal "I
 * disconnected but I'm still here"; a disconnect is a transport event with no C2S packet.)
 * Non-op, client-side. Own server only.
 */
public class GUICloseAnomaly extends Module {
    public enum Mode { SILENT_CLOSE, DOUBLE_CLOSE, WRONG_SYNCID, DURING_TRANSACTION, CLOSE_REOPEN, MENU_RESURRECTION }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which abnormal close path to exercise.")
        .defaultValue(Mode.SILENT_CLOSE).build());
    private final Setting<Keybind> closeKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("silent-close-key")
        .description("SILENT_CLOSE: press this to close the open GUI client-side with NO close packet. Works INSIDE a GUI (unlike the module-toggle bind). Bind your own key.")
        .defaultValue(Keybind.none())
        .visible(() -> mode.get() == Mode.SILENT_CLOSE).build());
    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("DURING_TRANSACTION: economy command to interrupt (no leading slash).")
        .defaultValue("sell all").visible(() -> mode.get() == Mode.DURING_TRANSACTION).build());
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("attempts-per-tick").description("DURING_TRANSACTION: transaction+close pairs per tick (to hit the commit window).")
        .defaultValue(4).range(1, 40).sliderRange(1, 20)
        .visible(() -> mode.get() == Mode.DURING_TRANSACTION).build());
    private final Setting<Boolean> repeat = sgGeneral.add(new BoolSetting.Builder()
        .name("repeat").description("Keep firing each tick (DURING_TRANSACTION). Off = one-shot for the timing-independent modes.")
        .defaultValue(false).build());
    private final Setting<Integer> resurrectDelay = sgGeneral.add(new IntSetting.Builder()
        .name("resurrect-delay-ticks").description("MENU_RESURRECTION: ticks between closing on the server and resurrecting the menu client-side.")
        .defaultValue(5).range(0, 60).sliderRange(0, 20)
        .visible(() -> mode.get() == Mode.MENU_RESURRECTION).build());
    private final Setting<Integer> resurrectSlot = sgGeneral.add(new IntSetting.Builder()
        .name("resurrect-slot").description("MENU_RESURRECTION: slot to click on the resurrected (server-closed) syncId.")
        .defaultValue(0).range(0, 53).sliderRange(0, 53)
        .visible(() -> mode.get() == Mode.MENU_RESURRECTION).build());

    private int packetsSent = 0;
    private boolean done = false;
    private boolean wasPressed = false;

    // MENU_RESURRECTION state
    private Screen savedScreen;
    private ScreenHandler savedHandler;
    private int resurrectCountdown = -1;

    public GUICloseAnomaly() {
        super(AddonTemplate.DUPE_CATEGORY, "gui-close-anomaly",
            "Exercises abnormal GUI close paths (silent close, double close, wrong syncId, close-during-transaction, close+reopen, menu-resurrection) to find dangling plugin sessions.");
    }

    @Override
    public void onActivate() {
        packetsSent = 0;
        done = false;
        wasPressed = false;
        resurrectCountdown = -1;
        savedScreen = null;
        savedHandler = null;
        if (mode.get() == Mode.SILENT_CLOSE)
            info("SILENT_CLOSE armed — open a GUI and press your 'silent-close-key' to close it with NO packet (server keeps the session open).");
        else
            info("Mode %s — patch signal: session cleanup must run on disconnect/timeout/ANY abnormal close, not just the happy-path close.", mode.get());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // SILENT_CLOSE is keybind-driven and must work while a screen is focused, so poll the
        // dedicated bind's raw key state here (the tick loop keeps running inside a GUI).
        if (mode.get() == Mode.SILENT_CLOSE) {
            boolean pressed = closeKey.get().isPressed();
            if (pressed && !wasPressed) silentClose();
            wasPressed = pressed;
            return;
        }

        if (mode.get() == Mode.MENU_RESURRECTION) { menuResurrectionTick(); return; }

        boolean oneShot = mode.get() != Mode.DURING_TRANSACTION || !repeat.get();
        if (done && oneShot) return;

        boolean needsGui = mode.get() != Mode.CLOSE_REOPEN;
        if (needsGui && !GuiState.containerOpen(mc)) {
            info("Open a real container/shop GUI first (mode %s needs one).", mode.get());
            return;
        }

        var nh = mc.player.networkHandler;
        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;

        switch (mode.get()) {
            case DOUBLE_CLOSE -> {
                nh.sendPacket(new CloseHandledScreenC2SPacket(syncId));
                nh.sendPacket(new CloseHandledScreenC2SPacket(syncId));
                packetsSent += 2;
                info("Sent CloseHandledScreen twice for syncId %d — watch for double cleanup / double rollback.", syncId);
            }
            case WRONG_SYNCID -> {
                int wrong = syncId + 50;
                nh.sendPacket(new CloseHandledScreenC2SPacket(wrong));
                packetsSent++;
                info("Sent close for unopened syncId %d while %d is open — did the real GUI's close handler fire?", wrong, syncId);
            }
            case DURING_TRANSACTION -> {
                for (int i = 0; i < perTick.get(); i++) {
                    nh.sendChatCommand(command.get().trim());
                    nh.sendPacket(new CloseHandledScreenC2SPacket(syncId));
                    packetsSent += 2;
                }
            }
            case CLOSE_REOPEN -> {
                if (GuiState.containerOpen(mc)) {
                    nh.sendPacket(new CloseHandledScreenC2SPacket(syncId));
                    packetsSent++;
                }
                if (mc.crosshairTarget instanceof BlockHitResult bhr) {
                    nh.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, bhr, 0));
                    packetsSent++;
                    info("Closed syncId %d and re-interacted the crosshair block in the same tick — check the new syncId advances and inherits no state.", syncId);
                } else {
                    info("CLOSE_REOPEN needs a container block under your crosshair to re-open.");
                }
            }
            case SILENT_CLOSE, MENU_RESURRECTION -> { /* handled above */ }
        }
        done = true;
    }

    /**
     * Phase 1 (container open): snapshot the screen + handler, then tell the SERVER the container is
     * closed. Phase 2 (after resurrect-delay): re-attach the stale menu client-side and click it, so
     * the server receives a click on a syncId it already tore down.
     */
    private void menuResurrectionTick() {
        if (resurrectCountdown > 0) {
            if (--resurrectCountdown == 0) resurrectAndClick();
            return;
        }
        if (done) return;

        if (!GuiState.containerOpen(mc)) { info("Open a real container GUI first (MENU_RESURRECTION)."); return; }
        savedScreen = mc.currentScreen;
        savedHandler = mc.player.currentScreenHandler;
        int syncId = savedHandler.syncId;
        mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(syncId));
        packetsSent++;
        info("Saved menu (syncId %d) and told the server it's CLOSED. Resurrecting in %d ticks...", syncId, resurrectDelay.get());
        resurrectCountdown = Math.max(1, resurrectDelay.get());
        done = true;
    }

    private void resurrectAndClick() {
        if (savedScreen == null || savedHandler == null || mc.player == null) return;
        int syncId = savedHandler.syncId;
        mc.setScreen(savedScreen);                       // client re-shows the old screen
        mc.player.currentScreenHandler = savedHandler;   // re-attach the stale menu (public field)
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, savedHandler.getRevision(), (short) (int) resurrectSlot.get(), (byte) 0, SlotActionType.PICKUP,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
        warning("Clicked slot %d on RESURRECTED syncId %d (server closed it). If the server processed it, a torn-down session is still live — dupe/desync surface.",
            resurrectSlot.get(), syncId);
        savedScreen = null; savedHandler = null;
    }

    /** Close whatever screen is open, client-side only, sending no close packet. */
    private void silentClose() {
        if (mc.currentScreen == null) { info("No GUI open to silent-close."); return; }
        int syncId = mc.player.currentScreenHandler != null ? mc.player.currentScreenHandler.syncId : -1;
        mc.setScreen(null); // client-side close; the C2S close packet is only sent by the ESC path, not this
        info("Silent-closed GUI (syncId %d) with NO close packet — the server still believes it is open. Now act elsewhere and watch for dangling state.", syncId);
    }

    @Override
    public void onDeactivate() {
        if (mode.get() != Mode.SILENT_CLOSE)
            info("Summary: %d packets sent in mode %s.", packetsSent, mode.get());
    }
}
