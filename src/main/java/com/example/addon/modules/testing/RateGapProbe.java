package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.KickClassifier;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;

/**
 * AUDIT: Rate-Gap Probe (the band between plugin throttling and Paper's packet-limiter)
 *
 * <p>There are two rate limits on a block action, and they are set independently: the PLUGIN's
 * own cooldown (e.g. a shop/claim/region plugin limiting clicks) and Paper's network
 * packet-limiter. When the plugin's limit is looser than the operator assumes — or absent — an
 * attacker can act far faster than intended while staying comfortably under Paper's limiter, so
 * nothing kicks and no cooldown fires. This probe RAMPS a block action's rate and reports two
 * thresholds: the rate at which the plugin pushed back (a cooldown message) and the rate at which
 * Paper's limiter engaged (a kick). The exploitable band is everything in between.
 *
 * <p>This complements packet-limiter-map (which maps Paper's ceiling for swing/tab/payload) and
 * command-rate-limit-probe (command-variant bypasses) — neither ramps BLOCK place/dig/interact,
 * which is where region/shop/claim plugins live.
 *
 * <p>Aim at a block the plugin governs (a shop sign, a claim block, a protected region edge).
 * Detection of the plugin push-back is a best-effort chat-keyword match — tune the keywords to
 * your plugin's cooldown message. Patch signal: rate-limit at the plugin's RESOLVED action with
 * a bound tighter than Paper's packet-limiter; never rely on the packet-limiter as your cooldown.
 * Non-op, client-side. Own server only.
 */
public class RateGapProbe extends Module {
    public enum Action { BLOCK_INTERACT, BLOCK_PLACE, BLOCK_DIG }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action").description("Which block action to ramp against the crosshair block.")
        .defaultValue(Action.BLOCK_INTERACT).build());
    private final Setting<Integer> startRate = sgGeneral.add(new IntSetting.Builder()
        .name("start-per-second").description("Actions/second to begin at.")
        .defaultValue(2).range(1, 100).sliderRange(1, 20).build());
    private final Setting<Integer> stepRate = sgGeneral.add(new IntSetting.Builder()
        .name("step-per-second").description("Increase actions/second by this each interval.")
        .defaultValue(2).range(1, 100).sliderRange(1, 20).build());
    private final Setting<Integer> maxRate = sgGeneral.add(new IntSetting.Builder()
        .name("max-per-second").description("Stop ramping at this rate (keep under Paper's all-packet limiter if you only want the plugin floor).")
        .defaultValue(60).range(2, 500).sliderRange(5, 200).build());
    private final Setting<Integer> intervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("interval-ticks").description("Ticks to hold each rate before stepping up.")
        .defaultValue(40).range(5, 400).sliderRange(10, 120).build());
    private final Setting<String> keywords = sgGeneral.add(new StringSetting.Builder()
        .name("cooldown-keywords").description("Comma-separated chat keywords that mean the plugin throttled you.")
        .defaultValue("cooldown,slow down,too fast,wait,please wait,try again").build());
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable-on-kick").description("Disable when kicked (limiter engaged).").defaultValue(true).build());

    private int perSecond, timer, tickInPeriod, sent;
    private int pluginLimitRate = -1, limiterRate = -1;

    public RateGapProbe() {
        super(AddonTemplate.TESTING_CATEGORY, "rate-gap-probe",
            "Ramps a block action's rate and reports the band between the plugin's cooldown and Paper's packet-limiter — the window an attacker acts in undetected.");
    }

    @Override
    public void onActivate() {
        perSecond = startRate.get();
        timer = intervalTicks.get();
        tickInPeriod = 0; sent = 0;
        pluginLimitRate = limiterRate = -1;
        info("Ramping %s from %d/s (step +%d every %d ticks, cap %d/s). Aim at the plugin-governed block.",
            action.get(), perSecond, stepRate.get(), intervalTicks.get(), maxRate.get());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) { info("Aim at a block."); return; }

        // Step the rate on schedule.
        if (--timer <= 0) {
            timer = intervalTicks.get();
            if (perSecond < maxRate.get()) {
                perSecond = Math.min(maxRate.get(), perSecond + stepRate.get());
                info("-> %d actions/s", perSecond);
            }
        }

        // Spread perSecond actions across 20 ticks.
        int period = Math.max(1, 20 / Math.max(1, perSecond));
        int burst = Math.max(1, perSecond / 20);
        if (++tickInPeriod >= period) {
            tickInPeriod = 0;
            for (int i = 0; i < burst; i++) fire(bhr);
        }
    }

    private void fire(BlockHitResult bhr) {
        var nh = mc.player.networkHandler;
        switch (action.get()) {
            case BLOCK_INTERACT, BLOCK_PLACE ->
                nh.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, bhr, 0));
            case BLOCK_DIG -> {
                nh.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, bhr.getBlockPos(), bhr.getSide(), 0));
                nh.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, bhr.getBlockPos(), bhr.getSide(), 0));
            }
        }
        sent++;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof GameMessageS2CPacket m && pluginLimitRate < 0) {
            String text = m.content().getString().toLowerCase();
            for (String kw : keywords.get().split(",")) {
                String k = kw.trim().toLowerCase();
                if (!k.isEmpty() && text.contains(k)) {
                    pluginLimitRate = perSecond;
                    warning("PLUGIN cooldown fired at ~%d actions/s (\"%s\").", perSecond, m.content().getString());
                    reportGapIfPossible();
                    break;
                }
            }
        } else if (event.packet instanceof DisconnectS2CPacket d) {
            limiterRate = perSecond;
            info("Disconnect at ~%d/s: %s", perSecond, KickClassifier.describe(d.reason().getString()));
        }
    }

    private void reportGapIfPossible() {
        if (pluginLimitRate > 0)
            info("Plugin throttles at ~%d/s. Any rate BELOW that runs undetected; the gap up to Paper's limiter is the attacker's window.", pluginLimitRate);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (limiterRate < 0) limiterRate = perSecond;
        warning("Left game at ~%d actions/s — likely Paper's limiter (or a plugin kick).", limiterRate);
        if (autoDisable.get() && isActive()) toggle();
    }

    @Override
    public void onDeactivate() {
        info("=== Rate-Gap report (%s) ===", action.get());
        info("  %d actions sent; ramp reached %d/s.", sent, perSecond);
        if (pluginLimitRate > 0) info("  Plugin cooldown floor: ~%d/s.", pluginLimitRate);
        else info("  No plugin cooldown detected up to %d/s — plugin is NOT throttling this action (finding, if it should).", perSecond);
        if (limiterRate > 0) info("  Paper limiter / kick ceiling: ~%d/s.", limiterRate);
        if (pluginLimitRate > 0 && limiterRate > pluginLimitRate)
            warning("  Exploitable band: ~%d/s .. ~%d/s (fast enough to abuse, slow enough to avoid the limiter).", pluginLimitRate, limiterRate);
    }
}
