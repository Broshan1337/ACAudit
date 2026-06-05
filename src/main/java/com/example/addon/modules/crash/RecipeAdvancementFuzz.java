package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.AdvancementTabC2SPacket;
import net.minecraft.network.packet.c2s.play.CraftRequestC2SPacket;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.util.Identifier;

/**
 * AUDIT: Recipe/Advancement Fuzz (low-attention C2S surfaces)
 *
 * PLATFORM: Bukkit-universal (server recipe/advancement handling + any plugin
 * listening to crafting/advancement events).
 *
 * The recipe-book and advancement systems are client-driven via packets plugin
 * developers rarely think about:
 *   CRAFT_GARBAGE   — CraftRequestC2SPacket with invalid/garbage NetworkRecipeId
 *                     values. The server must look the id up and auto-fill the grid;
 *                     an unchecked id is an unexpected lookup path.
 *   ADVANCEMENT_TAB — AdvancementTabC2SPacket opening nonexistent/garbage advancement
 *                     tabs at a high rate.
 *
 * Patch signal: validate recipe ids against the known recipe set before any
 * grid/inventory mutation, ignore unknown advancement tabs cheaply, and rate-limit
 * both — never let a client drive a lookup or allocation with an unbounded id.
 *
 * Run against your OWN local server only.
 */
public class RecipeAdvancementFuzz extends Module {
    public enum Mode { CRAFT_GARBAGE, ADVANCEMENT_TAB, BOTH }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which low-attention C2S surface to fuzz.")
        .defaultValue(Mode.BOTH).build()
    );
    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Packets sent each tick.")
        .defaultValue(4).min(1).sliderMax(20).build()
    );

    private final GracefulResponse gr = new GracefulResponse(sgGeneral);
    private final TestCadence cadence = new TestCadence(sgGeneral);
    private int n = 0;

    public RecipeAdvancementFuzz() {
        super(AddonTemplate.CRASH_CATEGORY, "recipe-advancement-fuzz",
            "Fuzzes CraftRequest (garbage recipe ids) and AdvancementTab (garbage tabs). Tests low-attention recipe/advancement C2S handling.");
    }

    @Override
    public void onActivate() { n = 0; gr.onActivate(); cadence.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        gr.tick();
        if (!cadence.shouldFire()) return;

        int syncId = mc.player.currentScreenHandler.syncId;
        for (int i = 0; i < packets.get(); i++) {
            boolean craft = mode.get() == Mode.CRAFT_GARBAGE || (mode.get() == Mode.BOTH && (n % 2 == 0));
            if (craft) {
                int id = switch (n % 4) { case 0 -> Integer.MAX_VALUE; case 1 -> -1; case 2 -> Integer.MIN_VALUE; default -> 999999; };
                mc.player.networkHandler.sendPacket(new CraftRequestC2SPacket(syncId, new NetworkRecipeId(id), n % 2 == 0));
            } else {
                mc.player.networkHandler.sendPacket(new AdvancementTabC2SPacket(
                    AdvancementTabC2SPacket.Action.OPENED_TAB, Identifier.of("acaudit", "tab" + n)));
            }
            n++;
        }
        gr.markFired();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { gr.onReceive(event.packet); }

    @Override
    public void onDeactivate() { gr.report(l -> info("%s", l)); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        gr.onKick();
        if (isActive()) toggle();
    }
}
