package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Cross-Context Transaction Overlap (axis 4)
 *
 * Plugins protect a single container, but two transaction CONTEXTS active at once
 * are rarely reconciled. This sends, in the same tick, a click on the currently
 * open container (one transaction context) AND a merchant-trade selection
 * (a second, villager, context) — so the server is asked to advance two distinct
 * transactions referencing potentially the same items.
 *
 * Honest boundary: a vanilla client has one screen open at a time, so this races
 * two context-bearing PACKETS rather than two literally-open GUIs. That is still
 * the realistic threat — a threat actor sends whatever the protocol accepts,
 * regardless of what the UI shows.
 *
 * Vulnerable server / plugin: processes both contexts against shared item state
 * without a cross-context lock -> the same item is consumed/credited in two
 * transactions.
 * Hardened server: a player has at most one active transaction context; packets
 * for any other context are rejected; item ownership is locked across contexts.
 * Fix: enforce a single authoritative transaction context per player; reject
 * trade/click packets that do not match the live context; lock item ownership
 * for the duration of any pending transaction.
 *
 * Open a container, enable (set trade-id to a villager trade you can reach).
 * Run against your OWN server only.
 */
public class TradeShopOverlap extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("container-slot").description("Slot to click in the open container context.")
        .defaultValue(0).range(0, 90).sliderRange(0, 53).build()
    );
    private final Setting<Integer> tradeId = sgGeneral.add(new IntSetting.Builder()
        .name("trade-id").description("Merchant trade index for the second context.")
        .defaultValue(0).range(0, 63).sliderRange(0, 12).build()
    );

    private final TickWindow window = new TickWindow(sgGeneral);
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

    private int ticksActive = 0, packetsSent = 0;

    public TradeShopOverlap() {
        super(AddonTemplate.DUPE_CATEGORY, "trade-shop-overlap",
            "Races a container click and a merchant-trade selection in one tick (two transaction contexts). Tests cross-context item locking.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        window.onActivate(); obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        obs.tick();
        if (!window.armed()) window.arm();
        if (!window.shouldFire()) return;

        var h = mc.player.currentScreenHandler;
        // Context A: the open container.
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            h.syncId, h.getRevision(), (short) (int) slot.get(), (byte) 0, SlotActionType.QUICK_MOVE,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
        // Context B: a villager trade selection, same tick.
        mc.player.networkHandler.sendPacket(new SelectMerchantTradeC2SPacket(tradeId.get()));
        packetsSent++;
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
