package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.HandledScreenAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: GUI Clicker
 *
 * While ANY container GUI is open (echest, trade menu, sell menu, custom plugin
 * inventory), hold the keybind to spam clicks on the slot your cursor is over.
 * Targets the hovered slot, so you don't need to know the plugin's slot layout -
 * just point at the real button/item and hammer it.
 *
 * Use case: drive race conditions in plugin GUIs (double-buy, double-claim,
 * double-sell, trade dupes) by issuing many valid clicks on one button within a
 * tick, faster than the plugin can mark the action consumed.
 *
 * Sends with the CURRENT revision so each click is individually well-formed -
 * any dupe that results is a concurrency flaw, not a malformed-packet flaw.
 *
 * Patch signal: per-inventory lock around the GUI click handler; mark the
 * listing/trade/sale consumed via compare-and-set; reject clicks whose revision
 * is older than the last processed one.
 */
public class GuiClicker extends Module {
    public enum Click {
        LEFT(SlotActionType.PICKUP, 0),
        RIGHT(SlotActionType.PICKUP, 1),
        SHIFT_LEFT(SlotActionType.QUICK_MOVE, 0),
        SHIFT_RIGHT(SlotActionType.QUICK_MOVE, 1),
        DROP(SlotActionType.THROW, 0);

        final SlotActionType action;
        final int button;
        Click(SlotActionType action, int button) { this.action = action; this.button = button; }
    }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key")
        .description("Hold this to spam-click the hovered slot. Bind it in the GUI.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_V))
        .build()
    );
    private final Setting<Click> click = sgGeneral.add(new EnumSetting.Builder<Click>()
        .name("click-type").description("Which click to send.")
        .defaultValue(Click.LEFT).build()
    );
    private final Setting<Integer> clicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick").description("Clicks sent per tick while the key is held.")
        .defaultValue(10).range(1, 200).sliderRange(1, 50).build()
    );
    private final Setting<Boolean> logHovered = sgGeneral.add(new BoolSetting.Builder()
        .name("log-hovered-slot")
        .description("Print the hovered slot id when you start clicking (helps map the GUI).")
        .defaultValue(false).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    private int lastLoggedSlot = -999;

    public GuiClicker() {
        super(AddonTemplate.DUPE_CATEGORY, "gui-clicker",
            "Hold a key to spam-click the slot under your cursor in any open GUI. Tests plugin-GUI click concurrency.");
    }

    @Override
    public void onActivate() { lastLoggedSlot = -999; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) return;
        if (!key.get().isPressed()) { lastLoggedSlot = -999; return; }

        Slot focused = ((HandledScreenAccessor) screen).meteor$getFocusedSlot();
        if (focused == null) return;

        int slotId = focused.id;
        if (logHovered.get() && slotId != lastLoggedSlot) {
            info("Spamming slot %d (%s x%d/tick)", slotId, click.get(), clicksPerTick.get());
            lastLoggedSlot = slotId;
        }

        int syncId = mc.player.currentScreenHandler.syncId;
        int revision = mc.player.currentScreenHandler.getRevision();

        for (int i = 0; i < clicksPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, revision,
                (short) slotId, (byte) click.get().button,
                click.get().action,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
