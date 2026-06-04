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
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * AUDIT: Chest-Shop / Sign-Shop Concurrency Race
 *
 * With a chest-shop or sign-shop in the crosshair, simultaneously spams the
 * block-use (open/buy) packet AND rapid QUICK_MOVE clicks on the container if
 * one is already open. Most chest-shop plugins process the block interaction on
 * the main thread while the container click arrives on the network thread;
 * without locking both paths, the buy action can be double-applied (money
 * withdrawn twice for one delivery, or item delivered twice for one payment).
 *
 * Patch signal: a per-player serialised lock must cover both the block-interaction
 * buy trigger AND any concurrent inventory-click processing that the same plugin
 * handles; the buy and item-delivery must be a single atomic unit.
 *
 * Look at the shop block (or have the shop GUI open), enable.
 */
public class ChestShopRace extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> interactionsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("interactions-per-tick").description("Block-use packets per tick (re-open/buy triggers).")
        .defaultValue(20).range(1, 500).sliderRange(1, 100).build()
    );
    private final Setting<Integer> clicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick").description("QUICK_MOVE container clicks per tick (if GUI is open).")
        .defaultValue(10).range(1, 200).sliderRange(1, 50).build()
    );
    private final Setting<Integer> shopSlot = sgGeneral.add(new IntSetting.Builder()
        .name("shop-slot").description("Slot to QUICK_MOVE in the shop GUI (e.g. buy-confirm slot).")
        .defaultValue(0).range(0, 53).sliderRange(0, 53).build()
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

    private int seq = 0;

    public ChestShopRace() {
        super(AddonTemplate.DUPE_CATEGORY, "chest-shop-race",
            "Spams shop block-use and container clicks simultaneously. Tests shop buy-trigger vs. click atomicity.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; seq = 0;
        obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        // Spam block-use on the shop block in crosshair
        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() != HitResult.Type.MISS) {
            for (int i = 0; i < interactionsPerTick.get(); i++) {
                mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, seq++));
            packetsSent++;
            }
        }

        // Simultaneously spam container clicks if a GUI is open
        var handler = mc.player.currentScreenHandler;
        if (handler != null && handler != mc.player.playerScreenHandler) {
            int syncId = handler.syncId;
            int rev = handler.getRevision();
            for (int i = 0; i < clicksPerTick.get(); i++) {
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) (int) shopSlot.get(), (byte) 0, SlotActionType.QUICK_MOVE,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
            }
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
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.onReceive(event.packet);
        if (!(event.packet instanceof GameMessageS2CPacket msg)) return;
        String text = msg.content().getString();
        if (!text.isBlank()) info("[Response] %s", text);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
