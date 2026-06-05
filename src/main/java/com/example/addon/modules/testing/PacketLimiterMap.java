package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.MalformedPayload;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.util.Hand;

/**
 * AUDIT: Packet Limiter Map (Paper packet-limiter thresholds)
 *
 * PLATFORM: Paper-specific (paper-global.yml packet-limiter); on Spigot/vanilla
 * there is typically no limiter, so a kick here is the application, not Paper.
 *
 * Paper rate-limits incoming packets (a global all-packets rate and per-packet-type
 * thresholds in paper-global.yml). Our crash-tab floods HIT this limiter but do not
 * MEASURE it. This module ramps the send rate of ONE chosen packet type until the
 * limiter throttles or kicks, and reports the rate at which it engaged — turning a
 * "flood was stopped" result into a concrete "Paper holds <type> at ~N packets/s",
 * which is the actually-useful finding for a server owner.
 *
 *   Patch signal (any well-implemented setup): keep the packet-limiter enabled and
 *     tuned; plugins must not assume an unbounded packet rate, because a platform
 *     change (Paper -> Spigot) removes the protection they silently relied on.
 *
 * Run on YOUR server only.
 */
public class PacketLimiterMap extends Module {
    public enum Type { SWING, TAB_COMPLETE, CUSTOM_PAYLOAD }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Type> type = sgGeneral.add(new EnumSetting.Builder<Type>()
        .name("packet-type").description("Which C2S packet type to ramp.")
        .defaultValue(Type.SWING).build()
    );
    private final Setting<Integer> startRate = sgGeneral.add(new IntSetting.Builder()
        .name("start-rate").description("Packets per tick to begin at.")
        .defaultValue(2).range(1, 200).sliderRange(1, 50).build()
    );
    private final Setting<Integer> stepRate = sgGeneral.add(new IntSetting.Builder()
        .name("step-rate").description("Increase packets-per-tick by this each interval.")
        .defaultValue(2).range(1, 100).sliderRange(1, 20).build()
    );
    private final Setting<Integer> intervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("interval-ticks").description("Ticks to hold each rate before stepping up.")
        .defaultValue(20).range(2, 200).sliderRange(5, 60).build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private int rate, timer, completionId, perSecond;
    private int sentThisInterval, responsesThisInterval;
    private boolean reported, throttleReported;

    public PacketLimiterMap() {
        super(AddonTemplate.TESTING_CATEGORY, "packet-limiter-map",
            "Ramps one packet type's rate until Paper's packet-limiter throttles/kicks, reporting the threshold. Maps the limiter instead of just hitting it.");
    }

    @Override
    public void onActivate() {
        rate = startRate.get(); timer = 0; completionId = 0; reported = false; throttleReported = false;
        perSecond = rate * 20; sentThisInterval = 0; responsesThisInterval = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        for (int i = 0; i < rate; i++) send();
        sentThisInterval += rate;

        if (++timer >= intervalTicks.get()) {
            timer = 0;
            // Silent-throttle detection: TAB_COMPLETE gets one response per request, so a
            // response rate well below the send rate means the limiter is dropping packets
            // WITHOUT kicking — a result our flood modules would otherwise read as "passed".
            if (type.get() == Type.TAB_COMPLETE && sentThisInterval >= 20 && !throttleReported) {
                double ratio = responsesThisInterval / (double) sentThisInterval;
                if (ratio < 0.5) {
                    throttleReported = true;
                    info("SILENT THROTTLE: at ~%d/s only %.0f%% of tab-complete requests got a response (no kick) — Paper's limiter is dropping packets silently.",
                        perSecond, ratio * 100);
                }
            }
            sentThisInterval = 0; responsesThisInterval = 0;
            rate += stepRate.get();
            perSecond = rate * 20;
            info("Ramped to %d packets/tick (~%d/s) of %s...", rate, perSecond, type.get());
        }
    }

    private void send() {
        switch (type.get()) {
            case SWING -> mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            case TAB_COMPLETE -> mc.player.networkHandler.sendPacket(new RequestCommandCompletionsC2SPacket(completionId++, "/a"));
            case CUSTOM_PAYLOAD -> mc.player.networkHandler.sendPacket(new CustomPayloadC2SPacket(new MalformedPayload(new byte[8])));
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof CommandSuggestionsS2CPacket) responsesThisInterval++;
        if (event.packet instanceof DisconnectS2CPacket d && !reported) {
            reported = true;
            String r = d.reason().getString();
            boolean limiter = r.toLowerCase().contains("packet") || r.toLowerCase().contains("spam") || r.toLowerCase().contains("too many") || r.toLowerCase().contains("limit");
            info("THRESHOLD: %s %s at ~%d packets/s (%d/tick). Reason: \"%s\"",
                type.get(), limiter ? "hit the packet-limiter" : "caused a kick", perSecond, rate, r);
        }
    }

    @Override
    public void onDeactivate() {
        if (!reported) info("No kick up to ~%d packets/s (%d/tick) of %s — limiter not engaged in range.", perSecond, rate, type.get());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
