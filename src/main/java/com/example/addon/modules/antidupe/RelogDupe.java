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
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
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
    /** How the disconnect is forced — a CLEAN quit and an abrupt KICK hit DIFFERENT server save paths. */
    public enum DisconnectMethod { CLEAN_QUIT, INVALID_SLOT_KICK, SETTINGS_FLOOD_KICK }
    /** What starts the action+disconnect: a keybind, an incoming teleport, or reaching a target position. */
    public enum AutoTrigger { KEYBIND, ON_TELEPORT, ON_POSITION }

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
    private final Setting<DisconnectMethod> method = sgGeneral.add(new EnumSetting.Builder<DisconnectMethod>()
        .name("disconnect-method")
        .description("CLEAN_QUIT = graceful close; INVALID_SLOT_KICK = SetCarriedItem(-1) so the server KICKS you; SETTINGS_FLOOD_KICK = flood client options to trip a kick. Kick vs quit persist inventory on different paths.")
        .defaultValue(DisconnectMethod.CLEAN_QUIT).build()
    );
    private final Setting<Integer> lagPackets = sgGeneral.add(new IntSetting.Builder()
        .name("lag-window-packets")
        .description("Filler packets sent immediately BEFORE the disconnect to widen the server's save/rollback window (0 = off).")
        .defaultValue(0).range(0, 2000).sliderRange(0, 400).build()
    );
    private final Setting<AutoTrigger> autoTrigger = sgGeneral.add(new EnumSetting.Builder<AutoTrigger>()
        .name("auto-trigger")
        .description("KEYBIND (manual), ON_TELEPORT (fire when the server teleports you), or ON_POSITION (fire when you reach the target).")
        .defaultValue(AutoTrigger.KEYBIND).build()
    );
    private final Setting<Double> targetX = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-x").defaultValue(0).visible(() -> autoTrigger.get() == AutoTrigger.ON_POSITION).build());
    private final Setting<Double> targetY = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-y").defaultValue(0).visible(() -> autoTrigger.get() == AutoTrigger.ON_POSITION).build());
    private final Setting<Double> targetZ = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-z").defaultValue(0).visible(() -> autoTrigger.get() == AutoTrigger.ON_POSITION).build());
    private final Setting<Double> tolerance = sgGeneral.add(new DoubleSetting.Builder()
        .name("position-tolerance").defaultValue(3.0).min(0.1).sliderRange(0.5, 16)
        .visible(() -> autoTrigger.get() == AutoTrigger.ON_POSITION).build());
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Press to run the action and disconnect (KEYBIND trigger).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_R))
        .visible(() -> autoTrigger.get() == AutoTrigger.KEYBIND).build()
    );

    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    private boolean wasPressed = false;
    private int countdown = -1; // -1 = idle
    private boolean triggered = false; // auto-trigger fires once per activation

    public RelogDupe() {
        super(AddonTemplate.DUPE_CATEGORY, "relog-dupe",
            "Performs an action then force-disconnects N ticks later. Tests save-on-quit ordering vs. in-flight transactions.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; wasPressed = false; countdown = -1; triggered = false;
        obs.onActivate(); preStress.onActivate(this);
        info("Tip: combine with gui-desync — hold GUI packets then trigger relog to race save-on-quit. Try INVALID_SLOT_KICK vs CLEAN_QUIT — they persist on different paths.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        if (countdown > 0) {
            countdown--;
            if (countdown == 0) { doDisconnect(); countdown = -1; }
            return;
        }

        boolean fire;
        if (autoTrigger.get() == AutoTrigger.KEYBIND) {
            boolean p = key.get().isPressed();
            fire = p && !wasPressed;
            wasPressed = p;
        } else if (autoTrigger.get() == AutoTrigger.ON_POSITION) {
            fire = !triggered && withinTarget();
        } else {
            fire = false; // ON_TELEPORT is handled in onReceivePacket
        }
        if (!fire) return;

        triggered = true;
        performAction();
        obs.markFired();
        if (delay.get() <= 0) doDisconnect();
        else countdown = delay.get();
    }

    private boolean withinTarget() {
        if (mc.player == null) return false;
        double dx = mc.player.getX() - targetX.get();
        double dy = mc.player.getY() - targetY.get();
        double dz = mc.player.getZ() - targetZ.get();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= tolerance.get();
    }

    private void performAction() {
        if (actionType.get() == ActionType.COMMAND) {
            info("Action: /%s", command.get());
            mc.player.networkHandler.sendChatCommand(command.get());
        } else {
            if (!GuiState.containerOpen(mc)) { warning("No container open."); return; }
            int syncId = mc.player.currentScreenHandler.syncId;
            int rev = mc.player.currentScreenHandler.getRevision();
            info("Action: THROW slot %d", slot.get());
            mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                syncId, rev, (short) (int) slot.get(), (byte) 1, SlotActionType.THROW,
                new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
            packetsSent++;
        }
    }

    private void doDisconnect() {
        if (mc.player == null) return;
        var nh = mc.player.networkHandler;

        // Widen the save/rollback window with filler packets right before the disconnect.
        for (int i = 0; i < lagPackets.get(); i++) {
            nh.sendPacket(new UpdateSelectedSlotC2SPacket(i % 9));
            packetsSent++;
        }

        switch (method.get()) {
            case CLEAN_QUIT -> {
                warning("Force-disconnecting now (CLEAN_QUIT).");
                nh.getConnection().disconnect(Text.literal("ACAudit: relog-dupe test"));
            }
            case INVALID_SLOT_KICK -> {
                warning("Sending SetCarriedItem(-1) to force a server KICK (invalid slot).");
                nh.sendPacket(new UpdateSelectedSlotC2SPacket(-1));
                packetsSent++;
            }
            case SETTINGS_FLOOD_KICK -> {
                warning("Flooding client options to trip a server kick.");
                for (int i = 0; i < Math.max(50, lagPackets.get()); i++) {
                    nh.sendPacket(new ClientOptionsC2SPacket(SyncedClientOptions.createDefault()));
                    packetsSent++;
                }
            }
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
    private void onReceivePacket(PacketEvent.Receive event) {
        obs.onReceive(event.packet);

        // ON_TELEPORT: fire the action + disconnect the moment the server teleports us.
        if (autoTrigger.get() == AutoTrigger.ON_TELEPORT && !triggered && countdown < 0
            && event.packet instanceof PlayerPositionLookS2CPacket && mc.player != null) {
            triggered = true;
            info("Teleport received — running action + disconnect.");
            performAction();
            obs.markFired();
            if (delay.get() <= 0) doDisconnect();
            else countdown = delay.get();
            return;
        }

        if (actionType.get() != ActionType.COMMAND) return;
        if (!(event.packet instanceof GameMessageS2CPacket msg)) return;
        String text = msg.content().getString();
        if (!text.isBlank()) info("[Response to '/%s'] %s", command.get(), text);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (isActive()) toggle();
    }
}
