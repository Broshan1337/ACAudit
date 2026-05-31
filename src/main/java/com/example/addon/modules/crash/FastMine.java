package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Fast-Mine (break-time validation test)
 *
 * Sends START_DESTROY_BLOCK immediately followed by STOP_DESTROY_BLOCK for the
 * block you're looking at, which tells the server "I finished breaking this"
 * with zero elapsed time. The well-known instant-mine technique.
 *
 * WHAT THIS ACTUALLY TESTS - and why it matters more than the packet-rate kick:
 * a packet-rate limiter only catches the blunt, high-volume version. The real
 * question is whether your server validates BREAK TIME at all. For each break,
 * the server should require that at least `blockHardness / effectiveToolSpeed`
 * seconds elapsed since START. If it doesn't, a cheater breaking ONE block every
 * few ticks (well under any rate kick) still mines diamonds instantly and you'd
 * never flag it.
 *
 * Run this at a LOW rate (1 cycle/tick) against your own server first: if the
 * block breaks despite the tool/hardness saying it should take seconds, your
 * server is missing break-time validation - that's the finding, independent of
 * the rate kick. Getting kicked at high rates is your rate limiter working; the
 * low-rate break succeeding is the vulnerability.
 *
 * Patch signal: server-side, record the START tick per (player, blockPos) and on
 * STOP/auto-complete require elapsed >= ceil(hardness / toolSpeed * 20) ticks,
 * accounting for haste/fatigue/underwater/on-ground. Reject early completions.
 */
public class FastMine extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> cyclesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("cycles-per-tick")
        .description("START+STOP pairs per tick on the looked-at block. Start at 1 to test break-time validation; raise to test the rate limiter.")
        .defaultValue(1).range(1, 100).sliderRange(1, 20).build()
    );
    private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("tick-delay")
        .description("Ticks to wait between cycles (0 = every tick).")
        .defaultValue(0).range(0, 40).sliderRange(0, 20).build()
    );
    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing").description("Swing the hand each cycle (looks more legitimate).")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private int timer = 0;

    public FastMine() {
        super(AddonTemplate.CRASH_CATEGORY, "fast-mine",
            "Instant-breaks the looked-at block via START/STOP packets. Tests server-side break-TIME validation (not just packet rate).");
    }

    @Override
    public void onActivate() { timer = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;
        if (timer > 0) { timer--; return; }

        BlockPos pos = hit.getBlockPos();
        Direction dir = hit.getSide();

        for (int i = 0; i < cyclesPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, dir));
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, dir));
            if (swing.get()) mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        }
        timer = tickDelay.get();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
