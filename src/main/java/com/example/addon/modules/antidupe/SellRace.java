package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;

/**
 * AUDIT: Sell / Economy Race (TOCTOU)
 *
 * Fires the same economy command N times within a single client tick, so the
 * server receives a burst before it finishes processing the first one. If the
 * plugin's flow is: read inventory -> compute payout -> remove item, and those
 * steps aren't atomic (no per-player lock / no inventory re-check), each copy
 * of the command can observe the item still present and pay out -> money dupe.
 *
 * Use with exactly one sellable item/stack in hand or inventory, then watch
 * your balance vs. how many items actually got consumed.
 *
 * Patch signal: wrap the sell in a per-player synchronous lock, re-read and
 * decrement the inventory atomically, and verify the item is still present
 * immediately before crediting.
 */
public class SellRace extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command")
        .description("Economy command to race. No leading slash. e.g. 'sell hand' or 'ah sell 100'.")
        .defaultValue("sell hand")
        .build()
    );
    private final Setting<Integer> burst = sgGeneral.add(new IntSetting.Builder()
        .name("burst")
        .description("Copies of the command sent in the same tick (the race window width).")
        .defaultValue(2).range(2, 50).sliderRange(2, 20).build()
    );
    private final Setting<Integer> attempts = sgGeneral.add(new IntSetting.Builder()
        .name("attempts")
        .description("How many bursts to fire before disabling.")
        .defaultValue(1).range(1, 100).sliderRange(1, 20).build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Ticks between bursts (lets balance/inventory settle so you can read the result).")
        .defaultValue(40).range(1, 200).sliderRange(5, 100).build()
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

    private int fired = 0;
    private int timer = 0;

    public SellRace() {
        super(AddonTemplate.DUPE_CATEGORY, "sell-race",
            "Fires an economy command as a same-tick burst (TOCTOU). Tests atomicity of sell/payout vs. item removal.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; fired = 0; timer = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        if (timer > 0) { timer--; return; }

        for (int i = 0; i < burst.get(); i++) {
            mc.player.networkHandler.sendChatCommand(command.get());
        packetsSent++;

        }
        fired++;
        info("Burst %d/%d sent (%dx '%s')", fired, attempts.get(), burst.get(), command.get());
        timer = delayTicks.get();
        if (fired >= attempts.get()) toggle();
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof GameMessageS2CPacket msg)) return;
        String text = msg.content().getString();
        if (!text.isBlank()) info("[Response] %s", text);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
