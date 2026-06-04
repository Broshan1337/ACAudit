package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;

/**
 * AUDIT: Combat-State Probe (combat↔movement boundary — axis 5)
 *
 * Minimal single-attack probe (NOT a kill-aura): sends exactly one attack at the
 * crosshair entity while in a movement state that should make the attack invalid,
 * testing whether combat and movement validation share state.
 *
 *   APEX          — attack at the top of a jump (airborne, vertical speed ~0)
 *                   where reach/visibility constraints differ from on-ground.
 *   MOVING        — attack during fast movement, exploiting any gap between the
 *                   position at send-time and at process-time (movement-compensated
 *                   reach).
 *   SPRINT_DESYNC — report a sprint state inconsistent with combat, then attack;
 *                   tests whether sprint-state is cross-checked during combat.
 *
 *   What it exploits: combat and movement validators are usually separate systems
 *     that rarely consult each other's state.
 *   Measurement AC: validates the attack in isolation; passes.
 *   Physics AC: may compute reach against the wrong position if it doesn't account
 *     for the same-tick movement.
 *   Intent AC: flags an attack that the player's movement state makes impossible.
 *   Fix: share movement state with combat validation; compute reach against the
 *     position at packet-receipt with movement compensation; verify sprint/pose
 *     consistency at attack time.
 *
 * Look at a target entity, press the key. Run against your OWN server only.
 */
public class CombatStateProbe extends Module {
    public enum Mode { APEX, MOVING, SPRINT_DESYNC }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which combat/movement boundary to probe.")
        .defaultValue(Mode.APEX).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one probe attack at the crosshair entity.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_8)).build()
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

    public CombatStateProbe() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "combat-state-probe",
            "Sends one attack while in an impossible movement state (jump apex / fast move / sprint desync). Tests combat↔movement state sharing.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; wasPressed = false;
        obs.onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        if (mc.targetedEntity == null) { warning("Look at a target entity first."); return; }

        // Establish the requested movement state immediately before the attack.
        switch (mode.get()) {
            case APEX -> mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);
            case MOVING -> { /* attack is sent mid-movement; observer captures the timing gap */ }
            case SPRINT_DESYNC -> mc.player.setSprinting(!mc.player.isSprinting());
        }

        mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(
            mc.targetedEntity, mc.player.isSneaking()));
        packetsSent += 2;
        obs.markSent();
        info("Sent probe attack (%s) on %s.", mode.get(), mc.targetedEntity.getType().toString());
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
