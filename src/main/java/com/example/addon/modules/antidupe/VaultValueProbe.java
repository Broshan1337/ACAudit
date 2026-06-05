package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;

/**
 * AUDIT: Vault Value Probe (transfer conservation-of-money)
 *
 * PLATFORM: Paper-specific (Vault economy).
 *
 * Where EconFuzz tests a single-account parser, this tests the TRANSFER path —
 * the two-account operation (/pay, /eco transfer) that withdraws from one balance
 * and deposits to another. Vault performs that as two separate double operations,
 * so a non-finite or pathological amount can break conservation: money created out
 * of nothing, destroyed, or moved while one side fails. The probe sends transfers
 * with amounts chosen to break the withdraw/deposit invariant and watches the
 * balance feedback for non-conservation.
 *
 *   What it exploits: withdraw and deposit not being atomic, and double amounts
 *     that survive one leg but not the other (NaN/Infinity/underflow/negative).
 *   Patch signal (any well-implemented economy): make transfers atomic (both legs
 *     succeed or neither), reject non-finite/negative amounts before either leg,
 *     and verify the total across both accounts is unchanged.
 *
 * Set a real second account you control as the target. Run on YOUR server only.
 */
public class VaultValueProbe extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<EconValues.Category> valueSet = sgGeneral.add(new EnumSetting.Builder<EconValues.Category>()
        .name("value-set")
        .description("Which class of amounts to send. TRANSFER (default) = the curated conservation-breaking set; pick BIG_SCIENTIFIC / SPECIAL_FLOATS / etc. to target a class, or ALL for everything.")
        .defaultValue(EconValues.Category.TRANSFER).build()
    );
    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("Transfer command template. {p}=target player, {n}=amount. No leading slash.")
        .defaultValue("pay {p} {n}").build()
    );
    private final Setting<String> target = sgGeneral.add(new StringSetting.Builder()
        .name("target").description("Second account you control (the transfer recipient).")
        .defaultValue("Notch").build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks between transfers (avoid the chat-spam kick).")
        .defaultValue(25).range(1, 200).sliderRange(10, 100).build()
    );
    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop").description("Restart the list instead of disabling at the end.")
        .defaultValue(false).build()
    );

    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private String[] values = EconValues.TRANSFER;
    private int index = 0, timer = 0, ticksActive = 0, packetsSent = 0;
    private String lastValue = null;

    public VaultValueProbe() {
        super(AddonTemplate.DUPE_CATEGORY, "vault-value-probe",
            "Fuzzes the two-account transfer (/pay) with amounts that break the withdraw/deposit invariant. Tests Vault transfer atomicity and conservation of money.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; index = 0; timer = 0;
        values = EconValues.forCategory(valueSet.get());
        info("Value-set: %s (%d values).", valueSet.get(), values.length);
        obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        if (timer > 0) { timer--; return; }

        lastValue = values[index % values.length];
        String cmd = command.get().replace("{p}", target.get()).replace("{n}", lastValue);
        info("[%d/%d] /%s", (index % values.length) + 1, values.length, cmd);
        mc.player.networkHandler.sendChatCommand(cmd);
        packetsSent++;
        obs.markFired();

        index++;
        timer = delayTicks.get();
        if (index >= values.length) {
            if (loop.get()) index = 0;
            else toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d transfers sent. Compare both accounts' totals before/after for non-conservation.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.onReceive(event.packet);
        if (event.packet instanceof GameMessageS2CPacket msg) {
            String text = msg.content().getString();
            if (!text.isBlank()) info("[Response to '%s'] %s", lastValue, text);
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
