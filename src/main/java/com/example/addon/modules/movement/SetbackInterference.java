package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Setback Interference (check-state-sharing probe)
 *
 * When an AC sets a player back it briefly holds a "pending setback" state, during
 * which the authoritative position is in flux: the server has decided where the
 * player belongs but the client has not yet confirmed it. The audit question is
 * whether OTHER checks running during that window evaluate against the correct
 * (pending-setback) baseline, or against the stale pre-setback position the cheat
 * just reported.
 *
 * The module drives a deliberately illegal primary hop to provoke a setback, then
 * the moment that setback arrives it fires a SECOND illegal hop inside the
 * interference window and measures (via MovementObserver) whether that second hop
 * earns its own correction or is silently absorbed — absorption being the signal
 * that the baseline went stale while a setback was pending.
 *
 *   What it exploits: checks sharing a position baseline that is momentarily
 *     inconsistent during setback processing.
 *   Measurement AC: each packet checked independently; usually robust here.
 *   Simulation AC: vulnerable if a pending setback is not applied to the baseline
 *     every dependent check uses.
 *   Intent AC: freezes all position-dependent checks against the pending-setback
 *     position until the client confirms the teleport.
 *   Fix (any well-implemented AC): apply the pending setback to the baseline of
 *     every check atomically, and ignore client movement until the setback
 *     teleport is confirmed.
 *
 * Run against your OWN server only.
 */
public class SetbackInterference extends Module {
    private enum State { PRIMARY, AWAIT, INTERFERE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> hopSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("hop-speed").description("Horizontal speed (b/t) of each illegal hop — must be clearly illegal so the primary provokes a setback.")
        .defaultValue(0.6).range(0.3, 3.0).sliderRange(0.3, 1.5).build()
    );
    private final Setting<Integer> awaitTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("await-timeout").description("Ticks to wait for the primary setback before giving up and retrying.")
        .defaultValue(15).range(3, 100).sliderRange(5, 40).build()
    );
    private final Setting<Integer> windowTicks = sgGeneral.add(new IntSetting.Builder()
        .name("window-ticks").description("Length of the interference window after a setback during which secondary hops are fired.")
        .defaultValue(3).range(1, 20).sliderRange(1, 10).build()
    );
    private final Setting<Integer> restTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rest-ticks").description("Idle ticks between cycles to let the server settle.")
        .defaultValue(20).range(0, 200).sliderRange(0, 80).build()
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

    private int ticksActive = 0;
    private State state = State.PRIMARY;
    private int timer, interfereLeft, rest;
    private int secondariesThisWindow, setbacksAtWindowStart;
    private int totalSecondaries, totalAbsorbed, cycles;
    private boolean sawSetback;

    public SetbackInterference() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "setback-interference",
            "Provokes a setback then fires a second illegal hop inside the pending-setback window. Tests whether checks use the stale pre-setback baseline.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; state = State.PRIMARY; timer = 0; interfereLeft = 0; rest = 0;
        secondariesThisWindow = 0; setbacksAtWindowStart = 0;
        totalSecondaries = 0; totalAbsorbed = 0; cycles = 0; sawSetback = false;
        obs.onActivate();
    }

    private void hop() {
        if (mc.player == null) return;
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

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        switch (state) {
            case PRIMARY -> {
                if (rest > 0) { rest--; return; }
                sawSetback = false;
                hop();                 // illegal primary hop to provoke a setback
                state = State.AWAIT; timer = 0;
            }
            case AWAIT -> {
                if (sawSetback) {
                    state = State.INTERFERE;
                    interfereLeft = windowTicks.get();
                    secondariesThisWindow = 0;
                    setbacksAtWindowStart = obs.setbackCount();
                } else if (++timer >= awaitTimeout.get()) {
                    // primary was silently accepted — that itself is notable, restart
                    state = State.PRIMARY; rest = restTicks.get();
                }
            }
            case INTERFERE -> {
                hop();                 // secondary illegal hop, inside the pending-setback window
                secondariesThisWindow++;
                if (--interfereLeft <= 0) {
                    int gained = obs.setbackCount() - setbacksAtWindowStart;
                    int absorbed = Math.max(0, secondariesThisWindow - gained);
                    totalSecondaries += secondariesThisWindow;
                    totalAbsorbed += absorbed;
                    cycles++;
                    state = State.PRIMARY; rest = restTicks.get();
                }
            }
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.onReceive(event.packet);
        if (event.packet instanceof PlayerPositionLookS2CPacket) sawSetback = true;
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d cycles, %d secondary hops in-window, %d absorbed.", ticksActive, cycles, totalSecondaries, totalAbsorbed);
            if (totalSecondaries > 0 && totalAbsorbed > 0)
                info("→ %d/%d in-window hops were NOT separately corrected: baseline likely went stale during setback. INVESTIGATE.",
                    totalAbsorbed, totalSecondaries);
            else if (totalSecondaries > 0)
                info("→ every in-window hop was independently corrected: setback baseline held — good.");
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
