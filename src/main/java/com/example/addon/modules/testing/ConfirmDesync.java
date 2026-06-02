package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;

/**
 * AUDIT: Confirm Desync
 *
 * Withholds the client's teleport-confirm packets. When the server teleports or
 * sets you back, it issues a teleport id and waits for your TeleportConfirm
 * before treating your position as authoritative again. Dropping that confirm
 * leaves the server holding pending state and tests how it handles a client that
 * never acknowledges a correction.
 *
 * This is the enforcement-side question behind AntiSetback: if a setback is
 * issued but never confirmed, does the server keep accepting your old-position
 * movement, re-send forever, or escalate? A robust server should time out the
 * unacknowledged teleport and refuse to register movement until confirmed.
 *
 * Patch signal: bound the wait for a teleport-confirm; while a teleport id is
 * outstanding, do not accept movement from the pre-teleport position; escalate
 * (freeze/kick) on repeated missing confirms. NOTE: blocks legit teleports while
 * active - expect desync; use it to probe enforcement, then disable.
 */
public class ConfirmDesync extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify").description("Log each withheld teleport confirm.")
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

    private int withheld = 0;

    public ConfirmDesync() {
        super(AddonTemplate.TESTING_CATEGORY, "confirm-desync",
            "Withholds teleport-confirm packets. Tests how the server handles unacknowledged setbacks/teleports.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; withheld = 0; }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof TeleportConfirmC2SPacket)) return;
        ticksActive++;
        event.cancel();
        withheld++;
        if (notify.get()) warning("Withheld teleport confirm #%d", withheld);
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
