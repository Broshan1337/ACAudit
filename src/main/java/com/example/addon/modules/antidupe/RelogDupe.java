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
 * AUDIT: Relog / Combat-Log Dupe (save-on-quit ordering)
 *
 * The single most common real-world dupe class. Performs a value-bearing action
 * (an economy command, or a container click that moves an item) and then forces
 * an immediate client disconnect a configurable number of ticks later - often
 * the SAME tick. The bet: the server saves the player's profile/inventory on
 * quit from a snapshot taken BEFORE the in-flight transaction commits, so the
 * action's effect (money credited, item moved) lands while the saved inventory
 * still shows the pre-action state -> on relog the item/money exists twice.
 *
 * Run it with a single sellable stack, vary disconnect-delay-ticks from 0
 * upward, and on each relog compare your balance + inventory against what should
 * have been consumed.
 *
 * Patch signal: the quit handler must FENCE on all in-flight transactions for
 * that player and persist only post-commit state (write-ahead / single-writer
 * per player). Never save inventory from a snapshot that a concurrent command
 * can still mutate; treat a disconnect mid-transaction as either fully-applied
 * or fully-rolled-back, never half.
 */
public class RelogDupe extends Module {
    public enum ActionType { COMMAND, CONTAINER_CLICK }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<ActionType> actionType = sgGeneral.add(new EnumSetting.Builder<ActionType>()
        .name("action").description("What to do right before disconnecting.")
        .defaultValue(ActionType.COMMAND).build()
    );
    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("Economy command to run (COMMAND action). No leading slash.")
        .defaultValue("sell hand")
        .visible(() -> actionType.get() == ActionType.COMMAND).build()
    );
    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Slot to THROW (CONTAINER_CLICK action).")
        .defaultValue(0).range(0, 200).sliderRange(0, 53)
        .visible(() -> actionType.get() == ActionType.CONTAINER_CLICK).build()
    );
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("disconnect-delay-ticks")
        .description("Ticks between the action and the disconnect. 0 = same tick (tightest race).")
        .defaultValue(0).range(0, 40).sliderRange(0, 20).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Press to run the action and disconnect.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_R)).build()
    );

    private boolean wasPressed = false;
    private int countdown = -1; // -1 = idle

    public RelogDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "relog-dupe",
            "Performs an action then force-disconnects N ticks later. Tests save-on-quit ordering vs. in-flight transactions.");
    }

    @Override
    public void onActivate() { wasPressed = false; countdown = -1; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (countdown > 0) {
            countdown--;
            if (countdown == 0) { doDisconnect(); countdown = -1; }
            return;
        }

        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        performAction();
        if (delay.get() <= 0) doDisconnect();
        else countdown = delay.get();
    }

    private void performAction() {
        if (actionType.get() == ActionType.COMMAND) {
            info("Action: /%s", command.get());
            mc.player.networkHandler.sendChatCommand(command.get());
        } else {
            if (mc.player.currentScreenHandler == null) { warning("No container open."); return; }
            int syncId = mc.player.currentScreenHandler.syncId;
            int rev = mc.player.currentScreenHandler.getRevision();
            info("Action: THROW slot %d", slot.get());
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) (int) slot.get(), (byte) 1, SlotActionType.THROW,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        }
    }

    private void doDisconnect() {
        if (mc.player == null) return;
        warning("Force-disconnecting now (relog-dupe).");
        mc.player.networkHandler.getConnection().disconnect(Text.literal("ACAudit: relog-dupe test"));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }
}
