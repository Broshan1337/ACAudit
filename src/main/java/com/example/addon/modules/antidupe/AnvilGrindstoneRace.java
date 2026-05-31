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
 * AUDIT: Anvil / Grindstone Output Race
 *
 * Anvil and grindstone output slots are virtual results computed from the input
 * slots, and taking the output also charges/refunds XP and consumes the inputs.
 * This fires a same-tick burst of QUICK_MOVE clicks on the OUTPUT slot (index 2
 * for both menus), racing several "take result" operations before the inputs and
 * XP are debited.
 *
 * If output extraction isn't atomic with input consumption + XP charge, each
 * click can observe full inputs and hand out a repaired/combined/disenchanted
 * item (and the XP payout) more than once - an item dupe and an XP/economy dupe.
 *
 * Set up inputs in the anvil/grindstone, look away, and fire.
 *
 * Patch signal: take-result validates the recipe, consumes inputs, and applies
 * the XP delta under a single lock before the next click; recompute the output
 * from authoritative input state at commit time.
 */
public class AnvilGrindstoneRace extends Module {
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> outputSlot = sgGeneral.add(new IntSetting.Builder()
        .name("output-slot").description("Result slot (anvil & grindstone = 2).")
        .defaultValue(2).range(0, 200).sliderRange(0, 10).build()
    );
    private final Setting<Integer> burst = sgGeneral.add(new IntSetting.Builder()
        .name("burst").description("QUICK_MOVE clicks on the output slot per fire.")
        .defaultValue(8).range(2, 100).sliderRange(2, 30).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one burst (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_K))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private boolean wasPressed = false;

    public AnvilGrindstoneRace() {
        super(AddonTemplate.DUPE_CATEGORY, "anvil-grindstone-race",
            "Same-tick QUICK_MOVE burst on the anvil/grindstone output slot. Tests output-vs-input+XP atomicity.");
    }

    @Override
    public void onActivate() { wasPressed = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;

        boolean fire;
        if (trigger.get() == Trigger.EACH_TICK) fire = true;
        else { boolean p = key.get().isPressed(); fire = p && !wasPressed; wasPressed = p; }
        if (!fire) return;

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        for (int i = 0; i < burst.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) (int) outputSlot.get(), (byte) 0, SlotActionType.QUICK_MOVE,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        }
        info("Fired %d output-slot quick-moves.", burst.get());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
