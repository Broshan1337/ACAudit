package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Portal Interaction Spam
 *
 * Spams block-use packets on the portal block in the crosshair, and simultaneously
 * sends position packets INTO the portal. The portal transition system batches a
 * deferred dimension transfer per interaction; flooding it probes whether the
 * transition pipeline (initiate transfer, create player entity in dest world,
 * clean up source world entity) handles rapid concurrent requests safely.
 *
 * Unguarded rapid portal interactions have caused duplication and inventory
 * corruption in past Paper versions (items straddling both worlds during handoff).
 *
 * Patch signal: gate the portal transition initiation — only one pending transfer
 * per player at a time, and enforce a cooldown between transition re-attempts.
 *
 * Look at a nether portal, enable. Run against your OWN local server only.
 */
public class PortalSpam extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> interactionsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("interactions-per-tick").description("Portal block-use packets per tick.")
        .defaultValue(50).range(1, 500).sliderRange(1, 200).build()
    );
    private final Setting<Boolean> moveInto = sgGeneral.add(new BoolSetting.Builder()
        .name("move-into").description("Also send a position packet into the portal each tick.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> autoMonitor = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-monitor")
        .description("Auto-enable Server Health Monitor to track TPS impact while this module runs.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    private int seq = 0;

    public PortalSpam() {
        super(AddonTemplate.CRASH_CATEGORY, "portal-spam",
            "Spams portal block-use packets + position packets into the portal. Tests portal transition pipeline under rapid re-initiation.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; seq = 0;
        if (autoMonitor.get()) {
            var shm = Modules.get().get(ServerHealthMonitor.class);
            if (shm != null && !shm.isActive()) shm.toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS) {
            warning("Look at a portal block.");
            return;
        }

        for (int i = 0; i < interactionsPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, seq++));
            packetsSent++;
        }

        if (moveInto.get()) {
            Vec3d center = Vec3d.ofCenter(hit.getBlockPos());
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                center.x, center.y, center.z, false, false));
            packetsSent++;
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
