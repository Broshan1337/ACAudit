package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Crafting Result-Slot Race
 *
 * In a crafting table (or 2x2 player grid), the result slot is virtual: pulling
 * from it consumes one of each grid ingredient and immediately recomputes the
 * result. This fires a same-tick burst of QUICK_MOVE clicks on the RESULT slot,
 * so several "take the output" operations race before the grid is decremented.
 *
 * If result extraction and grid consumption aren't atomic, each click in the
 * burst can observe a full grid and hand out an output -> more outputs than the
 * grid should support (a craft dupe). Set up a recipe in the grid, look away,
 * and fire.
 *
 * Patch signal: take-result must, under one lock, verify the recipe still
 * matches, produce exactly one output, and decrement the grid - all before the
 * next click is processed. Re-derive the result from grid state at commit time,
 * never from a stale slot snapshot.
 */
public class CraftGridRace extends Module {
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> resultSlot = sgGeneral.add(new IntSetting.Builder()
        .name("result-slot").description("Crafting result slot (crafting table = 0, player 2x2 = 0).")
        .defaultValue(0).range(0, 200).sliderRange(0, 10).build()
    );
    private final Setting<Integer> burst = sgGeneral.add(new IntSetting.Builder()
        .name("burst").description("QUICK_MOVE clicks on the result slot per fire.")
        .defaultValue(8).range(2, 100).sliderRange(2, 30).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one burst (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_C))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build()
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

    private boolean wasPressed = false;

    public CraftGridRace() {
        super(AddonTemplate.DUPE_CATEGORY, "craft-grid-race",
            "Same-tick QUICK_MOVE burst on the crafting result slot. Tests result-vs-grid-consumption atomicity.");
    }

    @Override
    public void onActivate() { wasPressed = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;

        boolean fire;
        if (trigger.get() == Trigger.EACH_TICK) fire = true;
        else { boolean p = key.get().isPressed(); fire = p && !wasPressed; wasPressed = p; }
        if (!fire) return;

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        for (int i = 0; i < burst.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) (int) resultSlot.get(), (byte) 0, SlotActionType.QUICK_MOVE,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
        info("Fired %d result-slot quick-moves.", burst.get());
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
