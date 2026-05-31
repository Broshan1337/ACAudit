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
 * AUDIT: Bundle Insert/Extract Desync
 *
 * Bundles hold their contents in an item component. Inserting/extracting is done
 * with normal PICKUP clicks on the bundle slot, and the server must reconcile the
 * bundle's component (its contents + weight) on every interaction. This fires a
 * burst of PICKUP clicks on a bundle slot with a STALE revision (0), so the
 * server sees rapid bundle mutations whose acknowledged state lags reality.
 *
 * Bundles have a history of count/component desync dupes; if the bundle's content
 * component is updated non-atomically with the cursor/slot stacks, items can be
 * extracted while still counted inside the bundle.
 *
 * Patch signal: treat a bundle interaction as one atomic transfer between the
 * bundle component and the cursor; recompute the bundle's contents+weight from
 * authoritative state and reject clicks whose revision doesn't match the server's
 * current sequence.
 */
public class BundleDupe extends Module {
    public enum Trigger { KEYBIND, EACH_TICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> bundleSlot = sgGeneral.add(new IntSetting.Builder()
        .name("bundle-slot").description("Slot holding the bundle.")
        .defaultValue(0).range(0, 200).sliderRange(0, 53).build()
    );
    private final Setting<Integer> burst = sgGeneral.add(new IntSetting.Builder()
        .name("burst").description("Stale-revision PICKUP clicks per fire.")
        .defaultValue(6).range(2, 100).sliderRange(2, 30).build()
    );
    private final Setting<Boolean> staleRevision = sgGeneral.add(new BoolSetting.Builder()
        .name("stale-revision").description("Send revision=0 instead of the current one.")
        .defaultValue(true).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one burst (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_V))
        .visible(() -> trigger.get() == Trigger.KEYBIND).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );

    private boolean wasPressed = false;

    public BundleDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "bundle-dupe",
            "Stale-revision PICKUP burst on a bundle slot. Tests bundle component/count reconciliation.");
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
        int rev = staleRevision.get() ? 0 : handler.getRevision();
        for (int i = 0; i < burst.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) (int) bundleSlot.get(), (byte) 0, SlotActionType.PICKUP,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        }
        info("Fired %d bundle clicks (rev %d).", burst.get(), rev);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
