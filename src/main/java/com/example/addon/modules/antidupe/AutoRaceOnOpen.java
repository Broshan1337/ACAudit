package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Auto Race-on-Open
 *
 * Detects any container/GUI opening and immediately fires a click burst at every
 * slot for a few ticks - before the plugin has finished building and locking the
 * menu. Fully automated: just open the GUI and watch the plugin's item/balance
 * accounting. Targets plugins that populate menus asynchronously or accept clicks
 * during the open handshake.
 *
 * Patch signal: don't accept any click until the menu is fully constructed and
 * locked; reject clicks that arrive before the open is acknowledged.
 */
public class AutoRaceOnOpen extends Module {
    public enum Action { PICKUP, QUICK_MOVE }
    public enum Target { CONTAINER_ONLY, ALL_SLOTS }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action").defaultValue(Action.QUICK_MOVE).build()
    );
    private final Setting<Target> target = sgGeneral.add(new EnumSetting.Builder<Target>()
        .name("target").description("Hit only the container's slots, or all incl. your inventory.")
        .defaultValue(Target.CONTAINER_ONLY).build()
    );
    private final Setting<Integer> clicksPerSlot = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-slot-per-tick").defaultValue(2).range(1, 50).sliderRange(1, 20).build()
    );
    private final Setting<Integer> duration = sgGeneral.add(new IntSetting.Builder()
        .name("duration-ticks").description("How long the burst runs after open.")
        .defaultValue(3).range(1, 40).sliderRange(1, 20).build()
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

    private int burst = 0;

    public AutoRaceOnOpen() {
        super(AddonTemplate.DUPE_CATEGORY, "auto-race-on-open",
            "On any GUI open, bursts clicks at all slots before the menu locks. Tests open-handshake locking. Fully automated.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; burst = 0; }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (event.screen instanceof HandledScreen) burst = duration.get();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        if (burst <= 0) return;
        if (mc.player == null || mc.player.currentScreenHandler == null) { burst = 0; return; }

        var handler = mc.player.currentScreenHandler;
        int total = handler.slots.size();
        int containerSlots = total - 36;
        int hi = target.get() == Target.ALL_SLOTS ? total : Math.max(0, containerSlots);
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        SlotActionType act = action.get() == Action.PICKUP ? SlotActionType.PICKUP : SlotActionType.QUICK_MOVE;

        for (int slot = 0; slot < hi; slot++) {
            for (int c = 0; c < clicksPerSlot.get(); c++) {
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) slot, (byte) 0, act,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
            }
        }
        burst--;
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket p)
            info("Server updated slot %d → %s (syncId %d)", p.getSlot(), p.getStack().getName().getString(), p.getSyncId());
        else if (event.packet instanceof InventoryS2CPacket p)
            info("Server resynced inventory (syncId %d, %d slots)", p.syncId(), p.contents().size());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
