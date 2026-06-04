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
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Event-Bypass Probe (axis 3 — packet layer vs plugin layer)
 *
 * Most anti-dupe/economy plugins validate inside InventoryClickEvent. But several
 * item-movement paths DO NOT fire that event:
 *
 *   OFFHAND_SWAP  — SWAP_ITEM_WITH_OFFHAND (a PlayerAction, not a click)
 *   CREATIVE_SET  — CreativeInventoryAction (separate creative path)
 *   DROP_ACTION   — DROP_ITEM PlayerAction (drops without a slot click)
 *
 * The module fires the selected path at sniped ticks; DupeObserver reports
 * whether the inventory changed with NO server resync (silent accept + item
 * delta) — the client-side proxy for "the plugin's validation event never ran,
 * because the server mutated inventory off a non-click code path".
 *
 * Vulnerable server / plugin: validates ClickSlot only; these paths slip through.
 * Hardened server: every item-movement path (offhand, creative, drop, pick) is
 * routed through the same authority + event surface, or rate/permission gated.
 * Fix: hook all movement paths, not just InventoryClickEvent; mirror validation
 * on offhand-swap, creative-set, drop and pick-item.
 *
 * Run against your OWN server only.
 */
public class EventBypassProbe extends Module {
    public enum Path { OFFHAND_SWAP, CREATIVE_SET, DROP_ACTION }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Path> path = sgGeneral.add(new EnumSetting.Builder<Path>()
        .name("path").description("Which non-click item-movement path to probe.")
        .defaultValue(Path.OFFHAND_SWAP).build()
    );
    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Slot to act on (CREATIVE_SET / DROP_ACTION).")
        .defaultValue(36).range(0, 45).sliderRange(0, 45).build()
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

    public EventBypassProbe() {
        super(AddonTemplate.DUPE_CATEGORY, "event-bypass-probe",
            "Moves items via offhand-swap / creative-set / drop (paths that skip InventoryClickEvent). Tests whether plugin validation covers non-click paths.");
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

        switch (path.get()) {
            case OFFHAND_SWAP -> mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            case DROP_ACTION -> mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.DROP_ALL_ITEMS, BlockPos.ORIGIN, Direction.DOWN));
            case CREATIVE_SET -> {
                ItemStack t = mc.player.getInventory().getStack(
                    slot.get() >= 36 && slot.get() <= 44 ? slot.get() - 36 : 0);
                ItemStack s = t.isEmpty() ? ItemStack.EMPTY : t.copy();
                mc.player.networkHandler.sendPacket(new CreativeInventoryActionC2SPacket(slot.get(), s));
            }
        }
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
