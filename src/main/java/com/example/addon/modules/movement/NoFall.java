package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: NoFall (spoof-ground)
 *
 * While falling far enough to take damage, periodically sends an onGround=true
 * movement packet without actually touching ground. A server that applies fall
 * damage from client-reported ground contact resets the fall accumulator and
 * deals no damage.
 *
 * Subtlety controls:
 *   one-shot            — send exactly one spoof per fall then stop. Tests
 *                         whether the AC catches a single false ground claim vs.
 *                         a repeated stream.
 *   spoof-interval-ticks — send a spoof every N ticks (continuous mode). Tests
 *                          timing-based detection.
 *   snap-to-floor       — send Math.floor(Y) rather than exact Y before spoofing.
 *                         Mimics a client that touched a block boundary, which can
 *                         bypass position-continuity checks.
 *
 * DETECTION: never compute fall damage from a client onGround flag. Track the
 * highest Y reached and the landing Y server-side from authoritative position,
 * and apply damage from that delta. An onGround=true with no solid block under
 * the reported position is itself a flag.
 */
public class NoFall extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> minFall = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-fall-distance")
        .description("Only spoof once fall distance exceeds this (vanilla damage starts >3).")
        .defaultValue(2.5).range(0.0, 10.0).sliderRange(0.0, 5.0).build()
    );
    private final Setting<Boolean> oneShot = sgGeneral.add(new BoolSetting.Builder()
        .name("one-shot")
        .description("Send exactly one spoof per fall then stay silent. Tests single-claim vs. repeated-claim detection.")
        .defaultValue(false).build()
    );
    private final Setting<Integer> spoofIntervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("spoof-interval-ticks")
        .description("Send a spoof every N ticks (continuous mode). Tests timing-based detection.")
        .defaultValue(1).range(1, 10).sliderRange(1, 5)
        .visible(() -> !oneShot.get()).build()
    );
    private final Setting<Boolean> snapToFloor = sgGeneral.add(new BoolSetting.Builder()
        .name("snap-to-floor")
        .description("Report Math.floor(Y) to mimic touching a block boundary. Can bypass position-continuity checks.")
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
    private boolean fired = false;
    private int intervalCounter = 0;
    private boolean wasOnGround = false;

    public NoFall() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "ac-no-fall",
            "Spoofs onGround while falling to cancel fall damage. Tests server-side fall-damage computation.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; fired = false; intervalCounter = 0; wasOnGround = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;

        boolean onGround = mc.player.isOnGround();
        if (!wasOnGround && onGround) { fired = false; intervalCounter = 0; }
        wasOnGround = onGround;

        if (onGround) return;
        if (mc.player.fallDistance <= minFall.get()) return;
        if (mc.player.getVelocity().y >= 0) return;

        if (oneShot.get()) {
            if (fired) return;
            fired = true;
        } else {
            intervalCounter++;
            if (intervalCounter < spoofIntervalTicks.get()) return;
            intervalCounter = 0;
        }

        double y = snapToFloor.get() ? Math.floor(mc.player.getY()) : mc.player.getY();
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            mc.player.getX(), y, mc.player.getZ(), true, mc.player.horizontalCollision));
        packetsSent++;
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
