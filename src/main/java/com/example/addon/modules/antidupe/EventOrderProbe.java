package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Event Order Probe (Bukkit event coverage & ordering)
 *
 * PLATFORM: Bukkit-universal.
 *
 * Bukkit fires different events for actions a plugin author may think are "the same
 * thing", and a client controls which packets it sends — hence which events fire,
 * and in what order. A plugin that guards an action by listening to only the
 * obvious event is bypassable through a sibling event; a plugin that assumes a
 * canonical open->interact->close order can be desynced by a synthetic order.
 *
 *   DROP_VARIANTS    — drop an item three ways that fire three different events:
 *                      PlayerActionC2SPacket DROP_ITEM (PlayerDropItemEvent),
 *                      a slot -999 "outside" click (InventoryClickEvent), and a
 *                      THROW slot action (InventoryClickEvent, different shape).
 *                      Does the plugin's drop guard catch all three?
 *   CLOSE_BEFORE_OPEN— send a CloseHandledScreen for a screen never opened, firing
 *                      InventoryCloseEvent with no matching InventoryOpenEvent.
 *   CLICK_AFTER_CLOSE— close the current screen, then click in it the same tick.
 *
 *   What it exploits: incomplete event coverage and unguarded event ordering.
 *   Patch signal (any well-implemented plugin): guard an action by EVERY event that
 *     can produce it, and never assume open/interact/close arrive in a fixed order
 *     — validate against the authoritative handler state each time.
 *
 * Run on YOUR server only.
 */
public class EventOrderProbe extends Module {
    public enum Mode { DROP_VARIANTS, CLOSE_BEFORE_OPEN, CLICK_AFTER_CLOSE }
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which event-coverage / ordering scenario to fire.")
        .defaultValue(Mode.DROP_VARIANTS).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one scenario (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_4))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks between scenarios (EACH_TICK trigger).")
        .defaultValue(15).range(1, 100).sliderRange(2, 40).build()
    );

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

    private int ticksActive = 0, packetsSent = 0, timer = 0;
    private boolean wasPressed = false;

    public EventOrderProbe() {
        super(AddonTemplate.DUPE_CATEGORY, "event-order-probe",
            "Fires the same effect via different Bukkit events and synthetic event orders (close-before-open, click-after-close). Tests plugin event coverage.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; timer = 0; wasPressed = false; obs.onActivate(); preStress.onActivate(this); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        boolean fire;
        if (trigger.get() == Trigger.EACH_TICK) { if (timer > 0) { timer--; return; } fire = true; timer = delayTicks.get(); }
        else { boolean p = key.get().isPressed(); fire = p && !wasPressed; wasPressed = p; }
        if (!fire) return;

        var h = mc.player.currentScreenHandler;
        int sid = h.syncId, rev = h.getRevision();

        switch (mode.get()) {
            case DROP_VARIANTS -> {
                // 1) PlayerDropItemEvent path
                mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.DROP_ITEM, BlockPos.ORIGIN, Direction.DOWN));
                // 2) InventoryClickEvent, outside ("-999") click path
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    sid, rev, (short) -999, (byte) 0, SlotActionType.PICKUP,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
                // 3) InventoryClickEvent, THROW action on the held slot
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    sid, rev, (short) mc.player.getInventory().getSelectedSlot(), (byte) 0, SlotActionType.THROW,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
                packetsSent += 3;
                info("Dropped one item via 3 distinct events (PlayerDropItem / outside-click / THROW).");
            }
            case CLOSE_BEFORE_OPEN -> {
                mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(sid + 50));
                packetsSent++;
                info("Sent CloseHandledScreen for unopened syncId %d (InventoryClose with no Open).", sid + 50);
            }
            case CLICK_AFTER_CLOSE -> {
                mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(sid));
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    sid, rev, (short) 0, (byte) 0, SlotActionType.PICKUP,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
                packetsSent += 2;
                info("Closed syncId %d then clicked in it the same tick.", sid);
            }
        }
        obs.markFired();
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
