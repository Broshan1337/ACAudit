package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;

/**
 * AUDIT: AntiSetback
 *
 * Cancels the server's position-correction (teleport / setback) packets so the
 * player is not rubber-banded back after a flagged move. This isolates a
 * critical question: is your AC's enforcement only a setback? If a cheater can
 * drop the correction packet and keep their illegal position, then detection
 * without a hard consequence (kick/ban/freeze) achieves nothing.
 *
 * DETECTION / HARDENING: enforcement must not depend on the client accepting a
 * correction. Track that a teleport-id was issued and require the matching
 * confirm; if the player keeps moving from the pre-setback position without
 * confirming, escalate to kick. Better, back setbacks with server-authoritative
 * position (the server simply refuses to register the illegal position at all).
 *
 * NOTE: cancelling these also blocks legitimate teleports (pearls, /tp, portals)
 * while active - expect desync. Use it only to probe enforcement, then disable.
 */
public class AntiSetback extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Log each setback packet that gets rejected.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public AntiSetback() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "anti-setback",
            "Rejects server position-correction packets. Tests whether AC enforcement survives a non-cooperative client.");
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket packet)) return;
        event.cancel();
        if (notify.get()) warning("Rejected setback (teleportId %d)", packet.teleportId());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
