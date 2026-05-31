package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Auction House Click Race
 *
 * With an /ah (or any plugin) GUI open, spams clicks on a single slot within
 * one tick using the CURRENT, VALID revision number. Each click is individually
 * well-formed, so this isn't a malformed-packet test - it's a pure concurrency
 * test: does the plugin process two clicks on the same button (buy / claim /
 * list-confirm) before the first one finishes and the listing/state updates?
 *
 * Typical results to look for:
 *   - buy button   -> two items received, charged once, or item delivered twice
 *   - claim button -> returns/coins collected twice
 *   - list button  -> item listed but also kept in inventory
 *
 * Patch signal: handle GUI clicks on a per-inventory lock, mark the listing
 * consumed atomically (compare-and-set), and ignore clicks whose revision is
 * older than the last processed one.
 */
public class AuctionRace extends Module {
    public enum Click {
        LEFT(SlotActionType.PICKUP, (byte) 0),
        RIGHT(SlotActionType.PICKUP, (byte) 1),
        SHIFT_LEFT(SlotActionType.QUICK_MOVE, (byte) 0),
        SHIFT_RIGHT(SlotActionType.QUICK_MOVE, (byte) 1);

        final SlotActionType action;
        final byte button;
        Click(SlotActionType action, byte button) { this.action = action; this.button = button; }
    }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot")
        .description("GUI slot of the button to spam (buy / claim / list-confirm).")
        .defaultValue(0).range(0, 200).sliderRange(0, 53).build()
    );
    private final Setting<Click> click = sgGeneral.add(new EnumSetting.Builder<Click>()
        .name("click-type")
        .description("Which click the target button expects.")
        .defaultValue(Click.LEFT).build()
    );
    private final Setting<Integer> clicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("Clicks fired in the same tick (race window width).")
        .defaultValue(5).range(2, 100).sliderRange(2, 30).build()
    );
    private final Setting<Integer> attempts = sgGeneral.add(new IntSetting.Builder()
        .name("attempts")
        .description("Bursts to fire before disabling.")
        .defaultValue(1).range(1, 100).sliderRange(1, 20).build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Ticks between bursts so state can settle.")
        .defaultValue(40).range(1, 200).sliderRange(5, 100).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private int fired = 0;
    private int timer = 0;

    public AuctionRace() {
        super(AddonTemplate.DUPE_CATEGORY, "auction-race",
            "Spams valid clicks on one GUI slot in a single tick. Tests concurrency handling of buy/claim/list buttons.");
    }

    @Override
    public void onActivate() { fired = 0; timer = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null || handler == mc.player.playerScreenHandler) {
            warning("No plugin GUI open - open the /ah menu first.");
            return;
        }
        if (timer > 0) { timer--; return; }

        for (int i = 0; i < clicksPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                handler.syncId,
                handler.getRevision(),          // valid, current revision
                (short) (int) slot.get(),
                click.get().button,
                click.get().action,
                new Int2ObjectOpenHashMap<>(),
                ItemStackHash.EMPTY
            ));
        }
        fired++;
        info("Burst %d/%d sent (%dx %s on slot %d)", fired, attempts.get(), clicksPerTick.get(), click.get(), slot.get());
        timer = delayTicks.get();

        if (fired >= attempts.get()) toggle();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
