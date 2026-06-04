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
 * AUDIT: Low-Hop Fly (onGround=true while airborne)
 *
 * Locks the player's Y at a fixed height above ground (default 0.42 blocks,
 * mimicking the peak of a vanilla jump) and reports onGround=true each tick.
 * This is the lowest-profile fly technique: the per-tick Y delta is zero, the
 * reported flag claims the player is grounded, and the horizontal speed is
 * unchanged — defeating ACs that flag only large Y deltas or onGround=false
 * while flying.
 *
 * Subtlety controls:
 *   surface-noise  — ±random variation on the hover Y each tick. Tests whether
 *                    the AC catches "perfectly still Y" vs. "Y fluctuating within
 *                    normal range".
 *   skip-unchanged — suppress the position packet when X/Z haven't changed.
 *                    Mimics a stationary real player and probes whether the AC
 *                    flags packet presence or packet content.
 *
 * DETECTION: onGround=true is only valid when there is a solid support block
 * at or immediately below the reported Y. The server must raycast the block
 * column below the claimed position; an onGround claim with no solid block
 * underneath is a flag regardless of per-tick delta or velocity values.
 * Never trust the client's ground flag — re-derive it server-side.
 */
public class LowHopFly extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> hoverHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("hover-height").description("Blocks above ground to hover at.")
        .defaultValue(0.42).range(0.1, 2.0).sliderRange(0.1, 1.0).build()
    );
    private final Setting<Double> surfaceNoise = sgGeneral.add(new DoubleSetting.Builder()
        .name("surface-noise")
        .description("Random ±Y variation per tick. Tests variance-based vs. zero-motion detection.")
        .defaultValue(0.002).range(0.0, 0.01).sliderRange(0.0, 0.008).build()
    );
    private final Setting<Boolean> skipUnchanged = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-unchanged")
        .description("Skip position packet when X/Z hasn't changed since last tick. Mimics stationary player.")
        .defaultValue(false).build()
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
    private double targetY = -1;
    private double lastX = Double.NaN, lastZ = Double.NaN;

    public LowHopFly() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "low-hop-fly",
            "Hovers with onGround=true. Tests server-side ground validation.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        if (mc.player != null) targetY = mc.player.getY() + hoverHeight.get();
        lastX = Double.NaN; lastZ = Double.NaN;
        obs.onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;
        obs.tick();
        mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);

        double noise = surfaceNoise.get();
        double sendY = targetY + (noise > 0 ? (Math.random() * 2 - 1) * noise : 0);
        double cx = mc.player.getX(), cz = mc.player.getZ();
        mc.player.setPosition(cx, sendY, cz);

        if (skipUnchanged.get() && !Double.isNaN(lastX) && cx == lastX && cz == lastZ) return;

        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            cx, sendY, cz, true, mc.player.horizontalCollision));
        packetsSent++;
        obs.markSent();
        lastX = cx; lastZ = cz;
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
