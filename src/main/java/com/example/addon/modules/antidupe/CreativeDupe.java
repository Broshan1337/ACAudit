package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;

/**
 * AUDIT: Creative-Path Economy Bypass (axis 3 — packet layer vs plugin layer)
 *
 * Creative inventory edits travel via CreativeInventoryActionC2SPacket, a
 * COMPLETELY SEPARATE code path from the normal ClickSlot flow — it does not
 * fire the InventoryClickEvent most economy/anti-dupe plugins hook. A plugin
 * that validates every ClickSlot but forgets the creative path lets a player in
 * (or briefly granted) creative materialise sellable items for free, then sell
 * them through the normal economy.
 *
 * This sets a configurable item+count directly into a slot via the creative
 * packet and (optionally) immediately quick-moves it, so DupeObserver can show
 * whether the item appeared with no plugin validation and whether it then sold.
 *
 * What a vulnerable server does: applies the creative set-slot without routing it
 * through the same authority/checks as a normal click -> free items the economy
 * then honours.
 * What a hardened server does: gates the creative packet on genuine creative
 * permission AND runs the same anti-dupe/authority checks (or refuses creative
 * inventory edits in economy worlds entirely).
 * Fix: never trust the creative path as implicitly authorised; validate it with
 * the same lock/permission/event surface as ClickSlot, or disable it where the
 * economy runs.
 *
 * Requires creative mode (or a server that doesn't verify it — that's the test).
 * Run against your OWN server only.
 */
public class CreativeDupe extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> targetSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Inventory slot to materialise into (36-44 = hotbar).")
        .defaultValue(36).range(0, 45).sliderRange(0, 45).build()
    );
    private final Setting<Integer> count = sgGeneral.add(new IntSetting.Builder()
        .name("count").description("Stack size to set.")
        .defaultValue(64).range(1, 127).sliderRange(1, 64).build()
    );
    private final Setting<Boolean> sell = sgGeneral.add(new BoolSetting.Builder()
        .name("quick-move-after").description("Quick-move the materialised stack immediately (e.g. into an open shop).")
        .defaultValue(false).build()
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

    public CreativeDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "creative-dupe",
            "Materialises items via the creative inventory packet (bypasses InventoryClickEvent). Tests whether the creative path is validated like normal clicks.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        window.onActivate(); obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        if (!window.armed()) window.arm();
        if (!window.shouldFire()) return;

        // Use the stack currently in the target slot as the template (so it is a real, sellable item).
        ItemStack template = mc.player.getInventory().getStack(targetSlot.get() >= 36 && targetSlot.get() <= 44
            ? targetSlot.get() - 36 : 0);
        if (template.isEmpty()) { warning("Put a sellable item in the target slot first."); return; }
        ItemStack stack = template.copy();
        stack.setCount(count.get());

        mc.player.networkHandler.sendPacket(new CreativeInventoryActionC2SPacket(targetSlot.get(), stack));
        packetsSent++;
        obs.markFired();

        if (sell.get() && mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            var h = mc.player.currentScreenHandler;
            mc.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket(
                h.syncId, h.getRevision(), (short) (int) targetSlot.get(), (byte) 0,
                net.minecraft.screen.slot.SlotActionType.QUICK_MOVE,
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(),
                net.minecraft.screen.sync.ItemStackHash.EMPTY));
            packetsSent++;
        }
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
