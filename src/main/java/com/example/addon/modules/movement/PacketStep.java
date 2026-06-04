package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: Packet Step (bare Y-teleport without a jump arc)
 *
 * When the jump key is pressed, sends N rising position packets claiming to
 * step straight upward at stepHeight blocks per packet — without the curved
 * Y trajectory a vanilla jump produces. Tests path-continuity validation:
 * can the server distinguish a legitimate step-up (follows a plausible jump
 * arc from the ground) from a raw vertical teleport (Y jumps discontinuously)?
 *
 * Subtlety controls:
 *   arc-noise      — ±random variation on each Y step. Tests whether the AC
 *                    catches fixed-interval steps or also catches randomised
 *                    discontinuous Y jumps.
 *   cooldown-ticks — minimum ticks between firing. Tests rapid-step detection.
 *
 * DETECTION: validate that each new Y value is reachable from the previous
 * one via the player's current vertical velocity and gravity; a jump from
 * Y=0 to Y=1 in one packet with no preceding upward velocity is illegal.
 * Track velocity continuity across consecutive position packets.
 */
public class PacketStep extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> stepHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("step-height").description("Y delta to claim each step packet.")
        .defaultValue(1.0).range(0.1, 5.0).sliderRange(0.1, 3.0).build()
    );
    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets").description("Position packets to send per key press.")
        .defaultValue(3).range(1, 20).sliderRange(1, 10).build()
    );
    private final Setting<Double> arcNoise = sgGeneral.add(new DoubleSetting.Builder()
        .name("arc-noise")
        .description("Random ±variation on each step Y. Tests fixed-interval vs. randomised step detection.")
        .defaultValue(0.005).range(0.0, 0.02).sliderRange(0.0, 0.015).build()
    );
    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ticks").description("Minimum ticks between step attempts.")
        .defaultValue(3).range(0, 10).sliderRange(0, 8).build()
    );

    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private boolean wasPressed = false;
    private int cooldown = 0;

    public PacketStep() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "packet-step",
            "Spoofs Y position upward without a jump arc. Tests path-continuity validation.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; wasPressed = false; cooldown = 0; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;
        obs.tick();

        if (cooldown > 0) { cooldown--; }

        boolean pressed = mc.options.jumpKey.isPressed();
        boolean fire = pressed && !wasPressed;
        wasPressed = pressed;

        if (!fire || cooldown > 0) return;

        double y = mc.player.getY();
        double noise = arcNoise.get();
        int count = packets.get();

        for (int i = 0; i < count; i++) {
            double jit = noise > 0 ? (Math.random() * 2 - 1) * noise : 0;
            y += stepHeight.get() + jit;
            boolean last = (i == count - 1);
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX(), y, mc.player.getZ(), last, false));
            packetsSent++;
        }
        obs.markSent();

        cooldown = cooldownTicks.get();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
