package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Respawn Model Reset (AC state continuity across respawn)
 *
 * PLATFORM: Bukkit-universal (audits the AC's per-life state handling).
 *
 * Respawn and dimension transitions are complex state changes, and an anticheat
 * must RESET its per-player movement model (prediction baseline, velocity history,
 * pending setbacks, violation context) cleanly on respawn. If pre-death state leaks
 * into post-respawn checks, the first moments after respawn are either a free pass
 * (stale-loose model) or a false-positive trap (stale-strict model). This module
 * waits for a respawn, then immediately fires an illegal speed hop inside the
 * post-respawn window and grades via MovementObserver whether it is corrected (model
 * reset cleanly) or silently accepted (pre-death state leaked).
 *
 *   Patch signal (any well-implemented AC): on respawn/dimension change, fully reset
 *     the player's movement model to the new spawn position before validating any
 *     post-respawn movement; never carry a pre-death baseline across the transition.
 *
 * Optionally auto-respawns to cycle the test. Run on YOUR server only.
 */
public class RespawnModelReset extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> autoRespawn = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-respawn").description("Send PERFORM_RESPAWN automatically when dead, to cycle the test.")
        .defaultValue(true).build()
    );
    private final Setting<Double> hopSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("hop-speed").description("Horizontal speed (b/t) of the post-respawn illegal hop (clearly illegal so a reset model corrects it).")
        .defaultValue(0.6).range(0.3, 3.0).sliderRange(0.3, 1.5).build()
    );
    private final Setting<Integer> windowTicks = sgGeneral.add(new IntSetting.Builder()
        .name("window-ticks").description("Ticks after respawn during which to fire the probe hops.")
        .defaultValue(8).range(1, 40).sliderRange(1, 20).build()
    );

    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, probeWindow = 0, respawns = 0;
    private boolean respawnRequested = false;

    public RespawnModelReset() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "respawn-model-reset",
            "Fires an illegal hop immediately after respawn to test whether the AC resets its movement model or lets pre-death state leak post-respawn.");
    }

    @Override
    public void onActivate() { ticksActive = 0; probeWindow = 0; respawns = 0; respawnRequested = false; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        if (autoRespawn.get() && mc.player.isDead() && !respawnRequested) {
            mc.player.networkHandler.sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.PERFORM_RESPAWN));
            respawnRequested = true;
        }

        if (probeWindow > 0) {
            probeWindow--;
            float fwd = mc.player.forwardSpeed, side = mc.player.sidewaysSpeed;
            double f, s;
            if (fwd == 0 && side == 0) { f = 1; s = 0; }
            else { double len = Math.sqrt(fwd * fwd + side * side); f = fwd / len; s = side / len; }
            double yaw = Math.toRadians(mc.player.getYaw());
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            Vec3d v = mc.player.getVelocity();
            mc.player.setVelocity((f * -sin + s * cos) * hopSpeed.get(), v.y, (f * cos + s * sin) * hopSpeed.get());
            obs.markSent();
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.onReceive(event.packet);
        if (event.packet instanceof PlayerRespawnS2CPacket) {
            respawns++;
            respawnRequested = false;
            probeWindow = windowTicks.get();   // open the post-respawn probe window
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d respawns probed.", ticksActive, respawns);
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
