package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.packet.PacketBlock;
import com.example.addon.packet.PacketDump;
import com.example.addon.packet.PacketInspectorScreen;
import com.example.addon.packet.PacketLog;
import com.example.addon.packet.SequenceStore;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: Packet Inspector & Editor
 *
 * A live C2S/S2C packet log with copy, edit and replay — turns ACAudit into a
 * packet-level audit platform. While active it captures every packet (including the
 * traffic of every other ACAudit module, since they all send through the network
 * handler) into a bounded buffer; open the screen to browse, filter, export, and —
 * for C2S record packets — edit one field and replay it to test server-side
 * validation (e.g. capture a ClickSlot, set slot out of range, replay, observe).
 *
 * Also records a sequence of outbound packets and replays it with scaled timing, to
 * test rate-limiting and timing validation with real captured traffic.
 *
 * Replaying edited packets sends real traffic to the server — run on YOUR server.
 */
public class PacketInspector extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> captureC2S = sgGeneral.add(new BoolSetting.Builder()
        .name("capture-c2s").description("Capture client->server packets.").defaultValue(true).build());
    private final Setting<Boolean> captureS2C = sgGeneral.add(new BoolSetting.Builder()
        .name("capture-s2c").description("Capture server->client packets.").defaultValue(true).build());
    private final Setting<Integer> bufferSize = sgGeneral.add(new IntSetting.Builder()
        .name("buffer-size").description("Max packets kept in the log (oldest drop).")
        .defaultValue(3000).range(64, 50000).sliderRange(500, 10000).build());
    private final Setting<Boolean> openOnActivate = sgGeneral.add(new BoolSetting.Builder()
        .name("open-on-enable").description("Open the inspector screen when this module is enabled.").defaultValue(true).build());
    private final Setting<Keybind> openKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("open-key").description("Reopen the inspector screen (while the module stays enabled and capturing).")
        .defaultValue(Keybind.none()).build());

    private record Pending(Packet<?> packet, long dueMs) {}
    private final List<Pending> queue = new ArrayList<>();
    private final List<PacketLog.Entry> sequence = new ArrayList<>();
    private boolean recording;
    private boolean prevKey;

    public PacketInspector() {
        super(AddonTemplate.TESTING_CATEGORY, "packet-inspector",
            "Live C2S/S2C packet log with filter, export, field-edit and replay, plus sequence record/replay. A packet-level audit platform. Own server only.");
    }

    @Override
    public void onActivate() {
        PacketLog.setCapacity(bufferSize.get());
        queue.clear();
        if (openOnActivate.get()) open();
    }

    @Override
    public void onDeactivate() {
        queue.clear();
        recording = false;
    }

    /** Open the inspector GUI (on the render thread). */
    public void open() {
        mc.execute(() -> mc.setScreen(new PacketInspectorScreen()));
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (captureC2S.get()) PacketLog.add(true, event.packet);
        if (recording) sequence.add(new PacketLog.Entry(0, System.currentTimeMillis(), true, event.packet));
        if (PacketBlock.isBlocked(event.packet)) event.cancel();   // drop blocked outbound type
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (captureS2C.get()) PacketLog.add(false, event.packet);
        if (PacketBlock.isBlocked(event.packet)) event.cancel();   // drop blocked inbound type
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        boolean now = openKey.get().isPressed();
        if (now && !prevKey) open();
        prevKey = now;

        if (!queue.isEmpty()) {
            long t = System.currentTimeMillis();
            queue.removeIf(p -> {
                if (t >= p.dueMs()) { sendNow(p.packet()); return true; }
                return false;
            });
        }
    }

    // ---- replay API (used by the GUI) ----

    /**
     * Send a packet once, now. Only ever called with outbound (C2S) packets — the GUI
     * gates replay on the captured direction, so this trusts the caller. Wrapped in a
     * guard so a packet the C2S encoder can't handle can't take the connection down.
     */
    public void sendNow(Packet<?> packet) {
        if (packet == null || mc.player == null || mc.getNetworkHandler() == null) return;
        try {
            mc.getNetworkHandler().sendPacket(packet);
        } catch (Throwable t) {
            warning("Could not replay %s: %s", PacketDump.typeName(packet), t.getMessage());
        }
    }

    /** Replay a packet {@code times} times, {@code delayTicks} apart (GUI only offers this for outbound packets). */
    public void replay(Packet<?> packet, int times, int delayTicks) {
        if (packet == null) return;
        long base = System.currentTimeMillis();
        for (int i = 0; i < Math.max(1, times); i++)
            queue.add(new Pending(packet, base + (long) i * Math.max(0, delayTicks) * 50L));
    }

    // ---- sequence record/replay ----

    public void startRecording() { sequence.clear(); recording = true; }
    public void stopRecording() { recording = false; }
    public boolean isRecording() { return recording; }
    public int sequenceSize() { return sequence.size(); }

    /** Replay the recorded outbound sequence with timing multiplied by {@code scale} (1=real, <1=faster). */
    public void replaySequence(double scale) {
        if (sequence.isEmpty()) return;
        long base = sequence.get(0).time();
        long now = System.currentTimeMillis();
        for (PacketLog.Entry e : sequence)
            if (e.outbound())
                queue.add(new Pending(e.packet(), now + (long) ((e.time() - base) * scale)));
    }

    /** Snapshot of the in-memory recorded sequence (for saving). */
    public List<PacketLog.Entry> recordedSequence() { return new ArrayList<>(sequence); }

    /** Replay a loaded saved sequence with timing multiplied by {@code scale} (saved items were outbound). */
    public void replaySaved(List<SequenceStore.Timed> items, double scale) {
        long now = System.currentTimeMillis();
        for (SequenceStore.Timed it : items)
            queue.add(new Pending(it.packet(), now + (long) (it.delayMs() * scale)));
    }
}
