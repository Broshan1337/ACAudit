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
import net.minecraft.text.Text;

/**
 * AUDIT: Ender Chest Desync Dupe
 *
 * The ender chest is special: its contents are stored on the PLAYER profile, not
 * the world block, and are shared across every ender chest and every dimension.
 * That makes it a prime desync target. With an ender chest GUI open, this
 * quick-moves its contents into your normal inventory and then force-disconnects
 * within a few ticks - so the move is in flight while the profile save fires.
 *
 * If the ender-chest write and the inventory write aren't a single atomic,
 * flushed-on-quit unit, you relog with the items in your inventory AND still in
 * the ender chest (or vice-versa). The same desync drives cross-dimension and
 * multi-session ender variants.
 *
 * Patch signal: ender-chest contents are part of the player's atomic save unit;
 * mutations must be committed and flushed before (or rolled back on) disconnect,
 * and fenced against a second concurrent session for the same account.
 */
public class EnderChestDesync extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("disconnect-delay-ticks")
        .description("Ticks between grabbing contents and disconnecting. 0 = same tick.")
        .defaultValue(0).range(0, 40).sliderRange(0, 20).build()
    );
    private final Setting<Boolean> grab = sgGeneral.add(new BoolSetting.Builder()
        .name("grab-contents").description("Quick-move the ender chest's slots into your inventory.")
        .defaultValue(true).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("With the ender chest open, press to run the race.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_J)).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    private boolean wasPressed = false;
    private int countdown = -1;

    public EnderChestDesync() {
        super(AddonTemplate.DUPE_CATEGORY, "enderchest-desync",
            "Grabs ender chest contents then force-disconnects. Tests atomic+flushed profile save and session fencing.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; wasPressed = false; countdown = -1;
        info("Tip: combine with shulker-race if items are in shulkers inside the ender chest.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;

        if (countdown > 0) {
            countdown--;
            if (countdown == 0) { doDisconnect(); countdown = -1; }
            return;
        }

        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        var handler = mc.player.currentScreenHandler;
        if (handler == null || handler == mc.player.playerScreenHandler) {
            warning("Open an ender chest first.");
            return;
        }
        if (grab.get()) grabContents();
        if (delay.get() <= 0) doDisconnect();
        else countdown = delay.get();
    }

    private void grabContents() {
        var handler = mc.player.currentScreenHandler;
        int containerSlots = handler.slots.size() - 36; // player inv is the 36 trailing slots
        if (containerSlots <= 0) return;
        int syncId = handler.syncId;
        int rev = handler.getRevision();
        for (int i = 0; i < containerSlots; i++) {
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) i, (byte) 0, SlotActionType.QUICK_MOVE,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
        info("Quick-moved %d ender slots.", containerSlots);
    }

    private void doDisconnect() {
        if (mc.player == null) return;
        warning("Force-disconnecting now (enderchest-desync).");
        mc.player.networkHandler.getConnection().disconnect(Text.literal("ACAudit: enderchest-desync test"));
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }
}
