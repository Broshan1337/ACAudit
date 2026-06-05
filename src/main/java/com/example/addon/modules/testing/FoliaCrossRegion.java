package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

/**
 * AUDIT: Folia Cross-Region (regionized-threading race trigger)
 *
 * PLATFORM: Folia-specific (real); on standard Paper this is a narrow no-op.
 *
 * Folia ticks separate world REGIONS on separate threads. Operations that span two
 * regions (an entity in one region attacked from another, a container straddling a
 * region boundary, a player acting mid-transition between regions) cross a thread
 * boundary that standard Paper never has — the genuine async-race surface. A client
 * cannot see exact region edges, but region boundaries fall on chunk-group lines, so
 * this fires the chosen action precisely while the player is on a chunk boundary,
 * maximizing the chance of straddling a region edge, and counts the attempts.
 *
 *   MOVE   — push hard across the boundary while sending movement (region transition
 *            mid-move).
 *   ENTITY — attack the crosshair target while on a boundary (cross-region hit).
 *   BLOCK  — start-destroy the crosshair block while on a boundary (cross-region
 *            interaction).
 *
 *   Patch signal (Folia / any threaded platform): operations crossing a region
 *     boundary must be scheduled onto the owning region's thread and validated
 *     against a consistent snapshot — never read/mutate the far region's live state
 *     from the acting region's thread.
 *
 * Honest limit: the client cannot observe the race; correlate the attempt count with
 * Folia's logs / the dupe-or-desync outcome. Run on YOUR Folia server.
 */
public class FoliaCrossRegion extends Module {
    public enum Mode { MOVE, ENTITY, BLOCK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which cross-region action to fire on chunk boundaries.")
        .defaultValue(Mode.ENTITY).build()
    );
    private final Setting<Integer> edgeWidth = sgGeneral.add(new IntSetting.Builder()
        .name("edge-width").description("How close (blocks) to a chunk boundary counts as 'on the edge'.")
        .defaultValue(1).range(0, 4).sliderRange(0, 3).build()
    );
    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ticks").description("Minimum ticks between fired actions.")
        .defaultValue(4).range(1, 40).sliderRange(1, 20).build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private int cooldown = 0, fired = 0, setbacks = 0;

    public FoliaCrossRegion() {
        super(AddonTemplate.TESTING_CATEGORY, "folia-cross-region",
            "Fires actions on chunk/region boundaries to trigger Folia cross-region thread races (move/entity/block). Correlate the count with Folia logs.");
    }

    @Override
    public void onActivate() { cooldown = 0; fired = 0; setbacks = 0; }

    private boolean onEdge() {
        int w = edgeWidth.get();
        int cx = MathHelper.floorMod(MathHelper.floor(mc.player.getX()), 16);
        int cz = MathHelper.floorMod(MathHelper.floor(mc.player.getZ()), 16);
        return cx <= w || cx >= 15 - w || cz <= w || cz >= 15 - w;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (cooldown > 0) cooldown--;
        if (cooldown > 0 || !onEdge()) return;

        switch (mode.get()) {
            case MOVE -> {
                // shove across the nearest boundary
                var v = mc.player.getVelocity();
                double dirX = ((MathHelper.floorMod(MathHelper.floor(mc.player.getX()), 16) < 8) ? -1 : 1) * 0.6;
                double dirZ = ((MathHelper.floorMod(MathHelper.floor(mc.player.getZ()), 16) < 8) ? -1 : 1) * 0.6;
                mc.player.setVelocity(dirX, v.y, dirZ);
                fired++;
            }
            case ENTITY -> {
                if (mc.targetedEntity == null) return;
                mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
                mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(mc.targetedEntity, mc.player.isSneaking()));
                fired++;
            }
            case BLOCK -> {
                if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
                BlockPos pos = hit.getBlockPos();
                mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
                fired++;
            }
        }
        cooldown = cooldownTicks.get();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket) setbacks++;
        else if (event.packet instanceof DisconnectS2CPacket d) warning("Disconnected during cross-region probe: \"%s\"", d.reason().getString());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d boundary actions fired, %d setbacks seen.", fired, setbacks);
            info("-> correlate with Folia logs / dupe-or-desync outcome; the client cannot observe the cross-region race directly.");
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
