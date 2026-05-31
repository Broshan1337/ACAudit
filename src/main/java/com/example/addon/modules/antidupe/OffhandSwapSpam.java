package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Offhand-Swap Spam
 *
 * Spams SWAP_ITEM_WITH_OFFHAND (a PlayerActionC2SPacket) - no GUI and no slot
 * needed. The mainhand/offhand swap is a frequently-overlooked item-movement
 * path: plugins that gate inventory clicks often don't rate-limit or validate
 * offhand swaps, making it a quiet dupe/race surface.
 *
 * Patch signal: rate-limit offhand swaps to vanilla (one per tick), make the
 * swap atomic, and route it through the same inventory authority as slot clicks.
 */
public class OffhandSwapSpam extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> swapsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("swaps-per-tick").description("SWAP_ITEM_WITH_OFFHAND packets per tick.")
        .defaultValue(10).range(1, 200).sliderRange(1, 50).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    public OffhandSwapSpam() {
        super(AddonTemplate.DUPE_CATEGORY, "offhand-swap-spam",
            "Spams offhand swaps (no GUI needed). Tests rate-limiting and atomicity of the offhand item-movement path.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        for (int i = 0; i < swapsPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
