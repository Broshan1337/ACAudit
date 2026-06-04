package com.example.addon.modules.crash;

import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;

/**
 * Shared firing-cadence engine (review criterion 5).
 *
 * Most vectors fire all-at-once every tick, which a blunt rate limiter catches.
 * Real attacks are more patient. This composition helper gives any continuous
 * module four cadences:
 *
 *   BURST              — fire every tick (the original behaviour; default).
 *   SLOW_BURN          — fire once every N ticks, sustained, to test whether the
 *                        AC has memory or silently resets its counters over time.
 *   BURST_THEN_SILENCE — fire for B ticks, go quiet for S ticks, repeat, to test
 *                        whether protection state resets between bursts.
 *   INTERLEAVED        — fire every tick but also emit innocuous legitimate
 *                        packets, so the malicious ones are buried in normal
 *                        traffic; tests behavioural vs. per-packet detection.
 *
 * The module calls shouldFire() each tick and, when INTERLEAVED, sends
 * legitRatio() legit packets via sendLegit().
 */
public final class TestCadence {
    public enum Mode { BURST, SLOW_BURN, BURST_THEN_SILENCE, INTERLEAVED }

    private final Setting<Mode> mode;
    private final Setting<Integer> slowBurnGap;
    private final Setting<Integer> burstTicks;
    private final Setting<Integer> silenceTicks;
    private final Setting<Integer> legitRatio;

    private int timer;
    private int phaseTick;

    public TestCadence(SettingGroup g) {
        mode = g.add(new EnumSetting.Builder<Mode>()
            .name("cadence")
            .description("Firing pattern. BURST = every tick; SLOW_BURN = sub-threshold sustained; BURST_THEN_SILENCE = fire/settle/fire; INTERLEAVED = hide among legit packets.")
            .defaultValue(Mode.BURST).build()
        );
        slowBurnGap = g.add(new IntSetting.Builder()
            .name("slow-burn-gap")
            .description("Ticks between sends in SLOW_BURN.")
            .defaultValue(10).range(1, 200).sliderRange(1, 100)
            .visible(() -> mode.get() == Mode.SLOW_BURN).build()
        );
        burstTicks = g.add(new IntSetting.Builder()
            .name("burst-ticks")
            .description("On-phase length in BURST_THEN_SILENCE.")
            .defaultValue(10).range(1, 200).sliderRange(1, 100)
            .visible(() -> mode.get() == Mode.BURST_THEN_SILENCE).build()
        );
        silenceTicks = g.add(new IntSetting.Builder()
            .name("silence-ticks")
            .description("Off-phase length in BURST_THEN_SILENCE.")
            .defaultValue(40).range(1, 400).sliderRange(1, 200)
            .visible(() -> mode.get() == Mode.BURST_THEN_SILENCE).build()
        );
        legitRatio = g.add(new IntSetting.Builder()
            .name("legit-per-vector")
            .description("Legitimate packets sent alongside the vector in INTERLEAVED mode.")
            .defaultValue(5).range(1, 100).sliderRange(1, 50)
            .visible(() -> mode.get() == Mode.INTERLEAVED).build()
        );
    }

    public Mode mode() { return mode.get(); }

    public void onActivate() { timer = 0; phaseTick = 0; }

    /** True if the vector should fire this tick. Call exactly once per module tick. */
    public boolean shouldFire() {
        switch (mode.get()) {
            case SLOW_BURN -> {
                if (timer > 0) { timer--; return false; }
                timer = slowBurnGap.get();
                return true;
            }
            case BURST_THEN_SILENCE -> {
                int cycle = burstTicks.get() + silenceTicks.get();
                boolean fire = (phaseTick % cycle) < burstTicks.get();
                phaseTick++;
                return fire;
            }
            default -> { return true; } // BURST, INTERLEAVED
        }
    }

    /** Legit packets to interleave this tick (0 unless INTERLEAVED). */
    public int legitRatio() { return mode.get() == Mode.INTERLEAVED ? legitRatio.get() : 0; }

    /** Emit innocuous, well-formed traffic to bury the vector among normal packets. */
    public static void sendLegit(ClientPlayerEntity player, int count) {
        if (player == null) return;
        for (int i = 0; i < count; i++) {
            if ((i & 1) == 0)
                player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(player.isOnGround(), player.horizontalCollision));
            else
                player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }
    }
}
