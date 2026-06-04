package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Packet Order Chaos
 *
 * Sends packets in causally impossible sequences: container clicks with no
 * container open, close-screen with wrong syncIds, block-use before interaction,
 * block-break start without stop, etc. The server must handle each out-of-order
 * packet safely — reject it cleanly rather than acting on stale state or throwing.
 *
 * Each sequence runs every tick in a configurable combination.
 *
 * Patch signal: all packet handlers must be idempotent against out-of-order
 * delivery — validate preconditions (container open, sequence numbers, causal
 * order) at the START of each handler, never assume state from a previous packet.
 *
 * Run against your OWN local server only.
 */
public class PacketOrderChaos extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("sequences-per-tick").description("Impossible-order sequences sent each tick.")
        .defaultValue(10).range(1, 200).sliderRange(1, 50).build()
    );
    private final Setting<Boolean> wrongSyncId = sgGeneral.add(new BoolSetting.Builder()
        .name("wrong-sync-id").description("Click slots with wrong/stale sync IDs when no container is open.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> closeBeforeClick = sgGeneral.add(new BoolSetting.Builder()
        .name("close-before-click").description("Send close-screen then immediately click the same syncId.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> breakWithoutStart = sgGeneral.add(new BoolSetting.Builder()
        .name("break-without-start").description("Send STOP_DESTROY_BLOCK without a prior START.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> swingBeforeAttack = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-before-attack").description("Swing arm packet interleaved at wrong times (high rate).")
        .defaultValue(true).build()
    );

    private final TestCadence cadence = new TestCadence(sgGeneral);
    private final PreStress preStress = new PreStress(sgGeneral);
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

    private int fakeSyncId = 99;

    public PacketOrderChaos() {
        super(AddonTemplate.CRASH_CATEGORY, "packet-order-chaos",
            "Sends packets in causally impossible sequences. Tests server-side precondition validation and causal-order enforcement.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; fakeSyncId = 99;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;

        for (int i = 0; i < perTick.get(); i++) {
            fakeSyncId = (fakeSyncId + 7) % 127 + 1; // cycle through 1-127

            if (wrongSyncId.get()) {
                // Click a slot on a container the server never sent us
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    fakeSyncId, 0, (short) 0, (byte) 0, SlotActionType.PICKUP,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
            }
            if (closeBeforeClick.get()) {
                mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(fakeSyncId));
            packetsSent++;
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    fakeSyncId, 0, (short) 1, (byte) 0, SlotActionType.QUICK_MOVE,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
            }
            if (breakWithoutStart.get()) {
                // STOP without a matching START
                mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, BlockPos.ORIGIN, Direction.UP));
            packetsSent++;
            }
            if (swingBeforeAttack.get()) {
                // Swing without attacking (just fills the pipeline)
                mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            packetsSent++;
                mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.OFF_HAND));
            packetsSent++;
            }
        }
        gr.markFired();
        TestCadence.sendLegit(mc.player, cadence.legitRatio());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            gr.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { gr.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        gr.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
