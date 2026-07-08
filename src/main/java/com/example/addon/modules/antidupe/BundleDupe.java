package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.BundleItemSelectedC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Bundle Insert/Extract Desync
 *
 * Bundles hold their contents in an item component. Inserting/extracting is done
 * with normal PICKUP clicks on the bundle slot, and the server must reconcile the
 * bundle's component (its contents + weight) on every interaction.
 *
 * <p>Two mechanisms:
 * <ul>
 *   <li>{@code STALE_REVISION} — a burst of PICKUP clicks on the bundle slot with a STALE
 *       revision (0), so the server sees rapid bundle mutations whose acknowledged state lags.</li>
 *   <li>{@code SELECT_BUNDLE_BURST} — right-click to pick the bundle up, then spam
 *       {@link BundleItemSelectedC2SPacket} (the packet that selects which item is "front" in the
 *       bundle) many times before putting it back. This is the modern 1.21.2+ bundle mechanism:
 *       rapidly re-selecting the bundle's item index races the server's component update so an
 *       item can be extracted while still counted inside the bundle. This is the distinct vector
 *       the well-known dupe clients use, which a stale-revision click burst does NOT reproduce.</li>
 * </ul>
 *
 * <p>Precondition for SELECT_BUNDLE_BURST: a bundle containing exactly one item works best.
 * Patch signal: treat a bundle interaction as one atomic transfer between the bundle component and
 * the cursor; recompute contents+weight from authoritative state, validate the selected index
 * against live contents, and reject a select/click burst faster than one interaction per tick.
 */
public class BundleDupe extends Module {
    public enum Trigger { KEYBIND, EACH_TICK }
    public enum Mode { STALE_REVISION, SELECT_BUNDLE_BURST }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Stale-revision click burst, or the select-bundle-item burst (modern mechanism).")
        .defaultValue(Mode.SELECT_BUNDLE_BURST).build()
    );
    private final Setting<Integer> bundleSlot = sgGeneral.add(new IntSetting.Builder()
        .name("bundle-slot").description("Handler slot holding the bundle (player inventory hotbar slot 0 = 36).")
        .defaultValue(36).range(0, 200).sliderRange(0, 53).build()
    );
    private final Setting<Integer> burst = sgGeneral.add(new IntSetting.Builder()
        .name("burst").description("Clicks (STALE_REVISION) or select packets (SELECT_BUNDLE_BURST) per fire.")
        .defaultValue(20).range(2, 200).sliderRange(2, 60).build()
    );
    private final Setting<Integer> bundleIndex = sgGeneral.add(new IntSetting.Builder()
        .name("selected-index").description("SELECT_BUNDLE_BURST: item index to select (-1 = deselect/none, the classic value).")
        .defaultValue(-1).range(-1, 63).sliderRange(-1, 8)
        .visible(() -> mode.get() == Mode.SELECT_BUNDLE_BURST).build()
    );
    private final Setting<Boolean> staleRevision = sgGeneral.add(new BoolSetting.Builder()
        .name("stale-revision").description("STALE_REVISION: send revision=0 instead of the current one.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.STALE_REVISION).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.KEYBIND).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires one burst (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_V))
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

    public BundleDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "bundle-dupe",
            "Bundle component desync via a stale-revision click burst OR a BundleItemSelected select-burst (modern mechanism). Tests bundle content/count reconciliation.");
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
        if (mode.get() == Mode.SELECT_BUNDLE_BURST) {
            // Right-click pickup the bundle, spam item-selection, then left-click it back.
            int rev = handler.getRevision();
            mc.player.networkHandler.sendPacket(pickup(syncId, rev, (byte) 1)); packetsSent++;
            for (int i = 0; i < burst.get(); i++) {
                mc.player.networkHandler.sendPacket(new BundleItemSelectedC2SPacket(bundleSlot.get(), bundleIndex.get()));
                packetsSent++;
            }
            mc.player.networkHandler.sendPacket(pickup(syncId, handler.getRevision(), (byte) 0)); packetsSent++;
            obs.markFired();
            info("Fired select-bundle burst: %d BundleItemSelected(idx %d) on slot %d.", burst.get(), bundleIndex.get(), bundleSlot.get());
        } else {
            int rev = staleRevision.get() ? 0 : handler.getRevision();
            for (int i = 0; i < burst.get(); i++) {
                mc.player.networkHandler.sendPacket(pickup(syncId, rev, (byte) 0));
                packetsSent++;
            }
            obs.markFired();
            info("Fired %d bundle clicks (rev %d).", burst.get(), rev);
        }
    }

    private ClickSlotC2SPacket pickup(int syncId, int rev, byte button) {
        return new ClickSlotC2SPacket(syncId, rev, (short) (int) bundleSlot.get(), button, SlotActionType.PICKUP,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY);
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
