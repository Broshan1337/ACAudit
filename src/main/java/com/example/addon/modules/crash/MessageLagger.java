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

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
