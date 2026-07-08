package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Merchant Trade Fuzz (villager / custom-merchant trade selection)
 *
 * <p>{@link SelectMerchantTradeC2SPacket} carries just a trade index, and the server is supposed
 * to validate it against the merchant's offer list and re-check stock/price at purchase time.
 * Custom-merchant plugins (server shops built on the villager UI) frequently trust the index and
 * read the result slot without re-validating — which opens two classic problems.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code OOB_INDEX} — select trade indices that don't exist (negative, huge). A vulnerable
 *       handler throws (AIOOBE/NPE) or, worse, resolves to an unintended offer. Tests index
 *       validation on the merchant handler.</li>
 *   <li>{@code CYCLE_RESTOCK} — rapidly select a trade and pull the result slot in the same tick,
 *       racing the offer's restock/disable so a sold-out or price-changed trade still yields an
 *       item (economy dupe). Requires the merchant GUI open.</li>
 * </ul>
 *
 * <p>Patch signal: validate the trade index against the live offer list, and re-check stock,
 * price and availability at the moment the result is taken — never trust the client's selected
 * index or a cached offer snapshot. Open the villager/merchant GUI first. Non-op, client-side.
 * Own server only.
 */
public class MerchantTradeFuzz extends Module {
    public enum Mode { OOB_INDEX, CYCLE_RESTOCK }

    private static final int RESULT_SLOT = 2; // merchant: 0,1 inputs, 2 result

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Trade-index fuzz or restock race.")
        .defaultValue(Mode.CYCLE_RESTOCK).build());
    private final Setting<Integer> maxIndex = sgGeneral.add(new IntSetting.Builder()
        .name("max-index").description("CYCLE_RESTOCK: cycle selected trade 0..this each tick. OOB_INDEX: upper bound of the huge indices tried.")
        .defaultValue(6).range(1, 200).sliderRange(1, 20).build());
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("actions-per-tick").description("Select(+take) operations per tick.")
        .defaultValue(6).range(1, 100).sliderRange(1, 30).build());
    private final Setting<Boolean> takeResult = sgGeneral.add(new BoolSetting.Builder()
        .name("take-result").description("CYCLE_RESTOCK: shift-click the result slot after each select (the dupe half).")
        .defaultValue(true).visible(() -> mode.get() == Mode.CYCLE_RESTOCK).build());

    private int packetsSent = 0, cycle = 0;

    public MerchantTradeFuzz() {
        super(AddonTemplate.DUPE_CATEGORY, "merchant-trade-fuzz",
            "Fuzzes villager/custom-merchant trade selection: out-of-range indices, and select+take restock races (economy dupe on custom-merchant shops).");
    }

    @Override
    public void onActivate() {
        packetsSent = 0; cycle = 0;
        info("Mode %s — open the villager/merchant GUI. Watch: an item from a sold-out/OOB trade, or a server exception.", mode.get());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (!GuiState.containerOpen(mc)) { info("Open the merchant GUI first."); return; }
        var nh = mc.player.networkHandler;
        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId, rev = handler.getRevision();

        for (int i = 0; i < perTick.get(); i++) {
            if (mode.get() == Mode.OOB_INDEX) {
                int[] bad = { -1, -1000, Integer.MIN_VALUE, maxIndex.get() + 1, 1000, Integer.MAX_VALUE };
                nh.sendPacket(new SelectMerchantTradeC2SPacket(bad[cycle % bad.length]));
                cycle++;
                packetsSent++;
            } else {
                nh.sendPacket(new SelectMerchantTradeC2SPacket(cycle % (maxIndex.get() + 1)));
                packetsSent++;
                if (takeResult.get()) {
                    nh.sendPacket(new ClickSlotC2SPacket(syncId, rev, (short) RESULT_SLOT, (byte) 0,
                        SlotActionType.QUICK_MOVE, new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
                    packetsSent++;
                }
                cycle++;
            }
        }
    }

    @Override
    public void onDeactivate() {
        info("Summary: %d packets sent (%d selects/cycle steps).", packetsSent, cycle);
    }
}
