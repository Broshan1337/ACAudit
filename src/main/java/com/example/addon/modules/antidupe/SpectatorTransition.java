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
 * AUDIT: Spectator Transition Boundary (axis 3)
 *
 * Spectators must not interact with inventories, but the boundary between
 * spectator and survival during a gamemode flip is sometimes exploitable: for a
 * tick or two the server may have updated one state (abilities) but not the other
 * (interaction gate), so an inventory action sent right at the flip can land
 * under the wrong ruleset.
 *
 * Note (honest boundary): a client cannot change its OWN gamemode — that is
 * server-authoritative. So this module SNIPEs a single inventory action each
 * armed tick while the OPERATOR flips the gamemode (/gamemode spectator <-> the
 * survival/creative mode). DupeObserver shows whether any action landed during
 * the transition. The module senses the flip via abilities/gamemode packets
 * forwarded to DupeObserver, but you create the flip.
 *
 * Vulnerable server: applies the inventory action against stale gamemode state
 * during the transition -> a spectator-window interaction is honoured.
 * Hardened server: gamemode transition atomically updates BOTH abilities and the
 * interaction gate; inventory packets during the flip are evaluated against the
 * post-transition state or rejected.
 * Fix: make the gamemode transition atomic; re-evaluate the interaction gate on
 * every inventory packet from authoritative current gamemode, not a cached flag.
 *
 * Run against your OWN server only.
 */
public class SpectatorTransition extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Slot to click during the transition.")
        .defaultValue(0).range(0, 90).sliderRange(0, 53).build()
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

    public SpectatorTransition() {
        super(AddonTemplate.DUPE_CATEGORY, "spectator-transition",
            "Snipes an inventory click during a gamemode flip (operator-triggered). Tests atomicity of the spectator interaction gate.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        window.onActivate(); obs.onActivate(); preStress.onActivate(this);
        info("Flip your gamemode (/gamemode spectator <-> survival) while this runs.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        ticksActive++;
        obs.tick();
        if (!window.armed()) window.arm();
        if (!window.shouldFire()) return;

        var h = mc.player.currentScreenHandler;
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            h.syncId, h.getRevision(), (short) (int) slot.get(), (byte) 0, SlotActionType.QUICK_MOVE,
            new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
        packetsSent++;
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
    private void onReceivePacket(PacketEvent.Receive event) { obs.survey(event.packet, l -> info("%s", l)); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
