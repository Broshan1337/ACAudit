package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.util.PlayerInput;

/**
 * AUDIT: Input Toggle Desync (sprint/sneak/input pose state)
 *
 * PLATFORM: Bukkit-universal (audits the AC's pose/sprint tracking).
 *
 * Sprint and sneak state are reported by the client (ClientCommandC2SPacket
 * START/STOP_SPRINTING, the sneak key) and the full input vector by
 * PlayerInputC2SPacket. An anticheat derives speed/pose tolerances from this state,
 * so toggling it faster than the server reconciles can desync what the AC believes
 * the player's pose/sprint is from what its movement model assumes -- e.g. claiming
 * "not sprinting" while moving at sprint speed, or flipping sneak every tick. This
 * floods the chosen toggles and grades via MovementObserver whether the resulting
 * movement is still bounded.
 *
 *   Patch signal (any well-implemented AC): reconcile sprint/sneak/pose state from
 *     authoritative movement, rate-limit client state toggles, and never let a
 *     stale or rapidly-flipped pose flag widen the movement tolerance.
 *
 * Run on YOUR server only.
 */
public class InputToggleDesync extends Module {
    public enum Mode { SPRINT, SNEAK, INPUT_FLOOD }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which client state to toggle: sprint start/stop, sneak press/release, or full input vector flood.")
        .defaultValue(Mode.SPRINT).build()
    );
    private final Setting<Integer> togglesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("toggles-per-tick").description("How many toggle packets to send each tick.")
        .defaultValue(2).range(1, 20).sliderRange(1, 10).build()
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

    private int ticksActive = 0, packetsSent = 0;
    private boolean state = false;

    public InputToggleDesync() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "input-toggle-desync",
            "Floods sprint/sneak/input toggles faster than the server reconciles. Tests whether the AC's pose/sprint state stays consistent.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; state = false; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        for (int i = 0; i < togglesPerTick.get(); i++) {
            state = !state;
            switch (mode.get()) {
                case SPRINT -> mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player,
                    state ? ClientCommandC2SPacket.Mode.START_SPRINTING : ClientCommandC2SPacket.Mode.STOP_SPRINTING));
                case SNEAK -> mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(
                    new PlayerInput(false, false, false, false, false, state, false))); // toggle sneak flag

                case INPUT_FLOOD -> mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(
                    new PlayerInput(state, !state, state, !state, state, !state, state)));
            }
            packetsSent++;
        }
        obs.markSent();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d toggles sent.", ticksActive, packetsSent);
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
