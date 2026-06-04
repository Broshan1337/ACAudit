package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
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

    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public OffhandSwapSpam() {
        super(AddonTemplate.DUPE_CATEGORY, "offhand-swap-spam",
            "Spams offhand swaps (no GUI needed). Tests rate-limiting and atomicity of the offhand item-movement path.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        for (int i = 0; i < swapsPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            packetsSent++;
        }
        obs.markFired();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.survey(event.packet, l -> info("%s", l)); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
