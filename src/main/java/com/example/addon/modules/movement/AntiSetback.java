package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * AUDIT: AntiSetback
 *
 * Cancels the server's position-correction packets so the player is not
 * rubber-banded back after a flagged move. This isolates a critical question:
 * is your AC's enforcement only a setback? If a cheater can drop the correction
 * and keep their illegal position, detection without hard consequences achieves
 * nothing.
 *
 * Subtlety controls:
 *   accept-ratio  — accept every Nth correction (1 = block all, 5 = keep 1 in 5).
 *                   Tests whether partial non-compliance is also caught.
 *   send-confirm  — send TeleportConfirmC2SPacket for the teleport ID without
 *                   applying the corrected position. This is the real bypass
 *                   pattern: the server thinks the teleport was acknowledged but
 *                   the player never moved to it. Tests confirm-vs-position
 *                   cross-check — the most valuable probe in this module.
 *   confirm-delay — ticks to hold before sending the confirm (simulates lag).
 *
 * Combination: always pair with the cheat that generates the setback (Speed,
 * StealthFly, Phase). The canonical Phase+AntiSetback combo tests wall-phase
 * bypass. With Blink: go silent → illegal move → flush → block correction.
 *
 * SCOPE — which targets this bites: an AC whose enforcement is a CLIENT-trusted
 * position correction (it sends the setback and assumes the client complies). It is
 * effectively INERT against any AC whose setback is server-authoritative and
 * transaction-confirmed — there the server holds you at the setback position and
 * ignores your movement until you confirm the teleport, so dropping the correction
 * client-side only desyncs you locally while the server keeps re-setting you back.
 * Use this to find out which kind your setup is; for a server-authoritative setback
 * the correct result is that this changes nothing, and that IS the pass.
 *
 * NOTE: cancelling these blocks legitimate teleports (pearls, /tp, portals)
 * while active. Use only to probe enforcement, then disable.
 */
public class AntiSetback extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> acceptRatio = sgGeneral.add(new IntSetting.Builder()
        .name("accept-ratio")
        .description("Accept 1 in every N corrections (1 = block all). Tests whether partial non-compliance is caught.")
        .defaultValue(1).range(1, 20).sliderRange(1, 10).build()
    );
    private final Setting<Boolean> sendConfirm = sgGeneral.add(new BoolSetting.Builder()
        .name("send-confirm")
        .description("Send TeleportConfirmC2SPacket without applying the position. Probes confirm-vs-position cross-check.")
        .defaultValue(false).build()
    );
    private final Setting<Integer> confirmDelay = sgGeneral.add(new IntSetting.Builder()
        .name("confirm-delay")
        .description("Ticks to wait before sending the confirm (simulates lag). Requires send-confirm on.")
        .defaultValue(0).range(0, 10).sliderRange(0, 5)
        .visible(sendConfirm::get).build()
    );
    private final Setting<Boolean> reassertPosition = sgGeneral.add(new BoolSetting.Builder()
        .name("reassert-position")
        .description("After rejecting a setback, actively re-send the PRE-setback position so the server's model stays inconsistent. Probes whether later moves are validated against the corrected model or the stale one we re-asserted.")
        .defaultValue(false).build()
    );
    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify").description("Log each setback packet that gets rejected.")
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

    private int ticksActive = 0, packetsSent = 0;
    private int received = 0;

    private record PendingConfirm(int teleportId, int sendAt) {}
    private final Deque<PendingConfirm> pendingConfirms = new ArrayDeque<>();

    private final MovementObserver obs = new MovementObserver(sgGeneral);

    public AntiSetback() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "anti-setback",
            "Rejects / spoofs server position-corrections. Tests whether AC enforcement survives a non-cooperative client. COMBINE WITH speed/phase — alone it does nothing; together it tests whether a dropped correction = a bypass.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; received = 0; pendingConfirms.clear(); obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        obs.tick();
        if (mc.player == null || pendingConfirms.isEmpty()) return;
        while (!pendingConfirms.isEmpty() && pendingConfirms.peek().sendAt() <= ticksActive) {
            int id = pendingConfirms.poll().teleportId();
            mc.player.networkHandler.sendPacket(new TeleportConfirmC2SPacket(id));
            packetsSent++;
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        obs.onReceive(event.packet);
        if (!(event.packet instanceof PlayerPositionLookS2CPacket packet)) return;
        received++;

        // Accept every Nth correction
        if (acceptRatio.get() > 1 && received % acceptRatio.get() == 0) {
            if (notify.get()) info("Accepted (ratio %d) setback id=%d", acceptRatio.get(), packet.teleportId());
            return;
        }

        event.cancel();
        obs.markSent();

        // Actively re-assert the stale (pre-setback) position so the server model stays inconsistent.
        if (reassertPosition.get() && mc.player != null) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.isOnGround(), false));
            packetsSent++;
        }

        if (sendConfirm.get()) {
            int delay = confirmDelay.get();
            if (delay == 0) {
                if (mc.player != null) {
                    mc.player.networkHandler.sendPacket(new TeleportConfirmC2SPacket(packet.teleportId()));
                    packetsSent++;
                }
            } else {
                pendingConfirms.add(new PendingConfirm(packet.teleportId(), ticksActive + delay));
            }
            if (notify.get())
                warning("Rejected setback id=%d [confirm %s, delay %d]",
                    packet.teleportId(), sendConfirm.get() ? "sent" : "suppressed", delay);
        } else {
            if (notify.get()) warning("Rejected setback id=%d", packet.teleportId());
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
