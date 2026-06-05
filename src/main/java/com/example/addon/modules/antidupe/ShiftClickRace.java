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
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Shift-Click Race (QUICK_MOVE burst across slot range)
 *
 * With a container open, fires a burst of QUICK_MOVE (shift-click) packets
 * across a configurable slot range all within the same tick. QUICK_MOVE is
 * special: the server must find the best destination slot, move the stack, and
 * update both sides atomically. Sending many simultaneous QUICK_MOVEs from
 * overlapping source slots gives the server multiple concurrent "find me a home"
 * requests that race against each other.
 *
 * If the server resolves each click against a snapshot rather than live state,
 * or if the slot search is not locked against concurrent moves, the same items
 * can be shift-clicked into two different destination slots simultaneously.
 *
 * Patch signal: QUICK_MOVE must atomically read, validate, and update BOTH the
 * source and destination slot under a single per-container lock. Re-query
 * destination slot availability at commit time, not at request time.
 */
public class ShiftClickRace extends Module {
    public enum Trigger { EACH_TICK, KEYBIND }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> startSlot = sgGeneral.add(new IntSetting.Builder()
        .name("start-slot").description("First slot in range to QUICK_MOVE.")
        .defaultValue(0).range(0, 200).sliderRange(0, 53).build()
    );
    private final Setting<Integer> endSlot = sgGeneral.add(new IntSetting.Builder()
        .name("end-slot").description("Last slot in range (inclusive).")
        .defaultValue(8).range(0, 200).sliderRange(0, 53).build()
    );
    private final Setting<Integer> burstPerSlot = sgGeneral.add(new IntSetting.Builder()
        .name("burst-per-slot").description("QUICK_MOVE copies per slot per fire.")
        .defaultValue(3).range(1, 50).sliderRange(1, 20).build()
    );
    private final Setting<Boolean> staleRevision = sgGeneral.add(new BoolSetting.Builder()
        .name("stale-revision").description("Send revision=0 instead of the current one.")
        .defaultValue(false).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.EACH_TICK).build()
    );
    private final Setting<meteordevelopment.meteorclient.utils.misc.Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fire key (KEYBIND trigger).")
        .defaultValue(meteordevelopment.meteorclient.utils.misc.Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_Z))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build()
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

    private boolean wasPressed = false;

    public ShiftClickRace() {
        super(AddonTemplate.DUPE_CATEGORY, "shift-click-race",
            "Same-tick QUICK_MOVE burst across a slot range. Tests QUICK_MOVE destination-search atomicity. COMBINE WITH a lag inducer (packet-spammer) to widen the race window.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; wasPressed = false;
        obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        obs.tick();

        boolean fire;
        if (trigger.get() == Trigger.EACH_TICK) fire = true;
        else { boolean p = key.get().isPressed(); fire = p && !wasPressed; wasPressed = p; }
        if (!fire) return;

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = staleRevision.get() ? 0 : handler.getRevision();
        int end = Math.min(endSlot.get(), handler.slots.size() - 1);

        for (int slot = startSlot.get(); slot <= end; slot++) {
            for (int b = 0; b < burstPerSlot.get(); b++) {
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) slot, (byte) 0, SlotActionType.QUICK_MOVE,
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
    private void onReceivePacket(PacketEvent.Receive event) { obs.survey(event.packet, l -> info("%s", l)); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
