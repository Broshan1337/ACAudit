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

/**
 * AUDIT: Revision / Sync Exploitation (axis 5, deep)
 *
 * Goes well past "send revision 0". Each mode probes a different assumption a
 * server might make about the click state-id:
 *
 *   OVERFLOW      — Integer.MAX_VALUE, MAX_VALUE+1 (wraps negative), -1, 0:
 *                   does revision arithmetic/compare survive wraparound?
 *   STALE_CONTEXT — a revision captured a few ticks ago (technically a valid
 *                   number, but from an earlier container state): does the server
 *                   validate revision against the CURRENT container or just that
 *                   it is a plausible value?
 *   FORWARD_JUMP  — currentRevision + N instead of +1: does the server trust a
 *                   client-provided revision that jumped ahead?
 *   MISMATCH_SYNC — a valid revision paired with a fabricated syncId (and vice
 *                   versa): is revision checked against the right container?
 *
 * Vulnerable server: accepts the click because the revision "looks valid",
 * mutating inventory on a stale/forged context -> replay & race dupes.
 * Hardened server: revision is an opaque per-container monotonic counter; a
 * click is accepted only if syncId matches the live session AND revision equals
 * the server's current value for THAT container; anything else -> resync, no
 * mutation.
 * Fix: validate (syncId == live) && (revision == currentRevisionForThatSyncId)
 * at the very start of the click handler; reject+resync on any mismatch.
 *
 * Open any container, enable. Run against your OWN server only.
 */
public class RevisionAbuse extends Module {
    public enum Mode { OVERFLOW, STALE_CONTEXT, FORWARD_JUMP, MISMATCH_SYNC }

    private static final int[] OVERFLOW_REVS = { Integer.MAX_VALUE, Integer.MIN_VALUE, -1, 0 };

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which revision/sync assumption to probe.")
        .defaultValue(Mode.OVERFLOW).build()
    );
    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Slot to click.")
        .defaultValue(0).range(0, 90).sliderRange(0, 53).build()
    );
    private final Setting<Integer> jump = sgGeneral.add(new IntSetting.Builder()
        .name("forward-jump").description("How far ahead of the real revision to jump (FORWARD_JUMP).")
        .defaultValue(5).range(2, 1000).sliderRange(2, 100)
        .visible(() -> mode.get() == Mode.FORWARD_JUMP).build()
    );
    private final Setting<Integer> staleAge = sgGeneral.add(new IntSetting.Builder()
        .name("stale-age").description("How many revisions behind to replay (STALE_CONTEXT).")
        .defaultValue(3).range(1, 50).sliderRange(1, 20)
        .visible(() -> mode.get() == Mode.STALE_CONTEXT).build()
    );

    private final TickWindow window = new TickWindow(sgGeneral);
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

    private int ticksActive = 0, packetsSent = 0;
    private int overflowIdx = 0;

    public RevisionAbuse() {
        super(AddonTemplate.DUPE_CATEGORY, "revision-abuse",
            "Probes revision overflow / stale-context / forward-jump / syncId-mismatch. Tests whether revision is validated against the right container.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; overflowIdx = 0;
        window.onActivate(); obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        obs.tick();
        if (!window.armed()) window.arm();
        if (!window.shouldFire()) return;

        var handler = mc.player.currentScreenHandler;
        int realSync = handler.syncId;
        int realRev = handler.getRevision();

        int syncId = realSync;
        int rev;
        switch (mode.get()) {
            case OVERFLOW -> { rev = OVERFLOW_REVS[overflowIdx % OVERFLOW_REVS.length]; overflowIdx++; }
            case STALE_CONTEXT -> rev = realRev - staleAge.get();
            case FORWARD_JUMP -> rev = realRev + jump.get();
            case MISMATCH_SYNC -> { rev = realRev; syncId = realSync + 1; } // valid revision, wrong container
            default -> rev = realRev;
        }

        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, rev, (short) (int) slot.get(), (byte) 0, SlotActionType.QUICK_MOVE,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
        obs.markFired();
        info("[%s] sent syncId=%d rev=%d (real: sync=%d rev=%d)", mode.get(), syncId, rev, realSync, realRev);
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
