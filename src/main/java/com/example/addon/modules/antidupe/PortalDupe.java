package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.world.World;

/**
 * AUDIT: Cross-Dimension / Portal Handoff Race
 *
 * Dimension changes are a hand-off: the player entity (and any in-flight item
 * operations) are serialized in one world and recreated in another. This module
 * fires a value-bearing action (an economy command, or a container click) on a
 * fixed interval and logs every dimension change, so an action is reliably
 * in flight while you cross a nether/end portal.
 *
 * If the dimension hand-off snapshots inventory/economy state before pending
 * operations resolve - or processes the same operation in both the source and
 * destination world - items or balance can survive the transfer twice.
 *
 * Run it, then walk a stack through a portal repeatedly and reconcile your
 * inventory/balance after each crossing.
 *
 * Patch signal: quiesce and commit all pending per-player transactions BEFORE
 * serializing the entity for transfer; the destination world must load
 * post-commit state only, and never re-run a source-world operation.
 */
public class PortalDupe extends Module {
    public enum ActionType { COMMAND, CONTAINER_CLICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<ActionType> actionType = sgGeneral.add(new EnumSetting.Builder<ActionType>()
        .name("action").defaultValue(ActionType.COMMAND).build()
    );
    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("Command to fire (COMMAND action). No leading slash.")
        .defaultValue("sell hand")
        .visible(() -> actionType.get() == ActionType.COMMAND).build()
    );
    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Slot to THROW (CONTAINER_CLICK action).")
        .defaultValue(0).range(0, 200).sliderRange(0, 53)
        .visible(() -> actionType.get() == ActionType.CONTAINER_CLICK).build()
    );
    private final Setting<Integer> intervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("interval-ticks").description("Ticks between actions (keep firing so one overlaps the crossing).")
        .defaultValue(2).range(1, 40).sliderRange(1, 20).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private int timer = 0;
    private RegistryKey<World> lastDim = null;

    public PortalDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "portal-dupe",
            "Fires an action on an interval and logs dimension changes. Tests transaction handoff across dimension boundaries.");
    }

    @Override
    public void onActivate() { timer = 0; lastDim = null; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        RegistryKey<World> dim = mc.world.getRegistryKey();
        if (lastDim != null && dim != lastDim) {
            warning("Dimension change: %s -> %s (action was in flight).",
                lastDim.getValue(), dim.getValue());
        }
        lastDim = dim;

        if (timer > 0) { timer--; return; }
        timer = intervalTicks.get();

        if (actionType.get() == ActionType.COMMAND) {
            mc.player.networkHandler.sendChatCommand(command.get());
        } else if (mc.player.currentScreenHandler != null) {
            var handler = mc.player.currentScreenHandler;
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                handler.syncId, handler.getRevision(), (short) (int) slot.get(), (byte) 1,
                SlotActionType.THROW, new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
