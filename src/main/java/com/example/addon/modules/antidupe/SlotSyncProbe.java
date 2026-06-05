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
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT: Slot/SyncId Probe (raw container-field normalization)
 *
 * PLATFORM: Bukkit-universal (results Platform-dependent — Paper normalizes some
 * fields Spigot does not).
 *
 * Container clicks carry a syncId (int), a slot index (short), a button (byte) and
 * a revision (int). Paper sanitizes SOME of these before the InventoryClickEvent
 * reaches plugins and passes others through raw — and plugin developers tend to
 * trust whatever Paper hands them. This module sends ClickSlotC2SPacket with each
 * field driven to boundary/illegal values (Integer.MAX_VALUE, +1 wrap, MIN, -1, the
 * vanilla -999 "outside" slot, short extremes) while holding the others valid, then
 * grades via DupeObserver whether the server rejects, kicks, normalizes, or accepts
 * it — mapping exactly which fields reach plugin code unfiltered.
 *
 *   What it exploits: a plugin trusting Paper to bound a field Paper actually
 *     passes through raw (and which breaks if the server moves to Spigot/a fork).
 *   Patch signal (any well-implemented server/plugin): validate every container
 *     field against the open handler's real bounds in the plugin itself; never
 *     depend on the platform to sanitize syncId/slot/revision for you.
 *
 * Open a container (or just your inventory) first. Run on YOUR server only.
 */
public class SlotSyncProbe extends Module {
    public enum Field { SYNC_ID, SLOT, BUTTON, REVISION, ALL }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Field> field = sgGeneral.add(new EnumSetting.Builder<Field>()
        .name("field").description("Which container field to drive to boundary/illegal values.")
        .defaultValue(Field.ALL).build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks between probes (avoids the packet-limiter / spam kick).")
        .defaultValue(10).range(1, 100).sliderRange(2, 40).build()
    );
    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop").description("Restart the probe list instead of disabling at the end.")
        .defaultValue(false).build()
    );

    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private record Probe(String label, int syncId, int slot, int button, int rev) {}
    private final List<Probe> probes = new ArrayList<>();
    private int index = 0, timer = 0, ticksActive = 0, packetsSent = 0;

    public SlotSyncProbe() {
        super(AddonTemplate.DUPE_CATEGORY, "slot-sync-probe",
            "Drives container syncId/slot/button/revision to illegal boundary values to map which fields the platform normalizes vs passes to plugins raw.");
    }

    private void buildProbes() {
        probes.clear();
        var h = mc.player.currentScreenHandler;
        int sid = h.syncId, rev = h.getRevision(), nslots = h.slots.size();
        int validSlot = Math.min(0, nslots - 1) >= 0 ? 0 : 0;
        Field f = field.get();
        boolean all = f == Field.ALL;

        if (all || f == Field.SYNC_ID)
            for (int v : new int[]{sid + 1, sid + 100, -1, 0, 127, Integer.MAX_VALUE, Integer.MIN_VALUE})
                probes.add(new Probe("syncId=" + v, v, validSlot, 0, rev));
        if (all || f == Field.SLOT)
            for (int v : new int[]{-1, -999, nslots, nslots + 64, 32767, -32768})
                probes.add(new Probe("slot=" + v, sid, v, 0, rev));
        if (all || f == Field.BUTTON)
            for (int v : new int[]{-1, 2, 40, 127, -128})
                probes.add(new Probe("button=" + v, sid, validSlot, v, rev));
        if (all || f == Field.REVISION)
            for (int v : new int[]{rev + 1, rev + 1000, -1, 0, Integer.MAX_VALUE, Integer.MIN_VALUE})
                probes.add(new Probe("revision=" + v, sid, validSlot, 0, v));
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; index = 0; timer = 0;
        obs.onActivate(); preStress.onActivate(this);
        if (mc.player != null) buildProbes();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        if (probes.isEmpty()) { buildProbes(); if (probes.isEmpty()) return; }
        if (timer > 0) { timer--; return; }

        Probe p = probes.get(index % probes.size());
        info("[%d/%d] %s", (index % probes.size()) + 1, probes.size(), p.label());
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            p.syncId(), p.rev(), (short) p.slot(), (byte) p.button(), SlotActionType.PICKUP,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
        obs.markFired();

        index++;
        timer = delayTicks.get();
        if (index >= probes.size()) {
            if (loop.get()) index = 0;
            else toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d probes sent.", ticksActive, packetsSent);
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
