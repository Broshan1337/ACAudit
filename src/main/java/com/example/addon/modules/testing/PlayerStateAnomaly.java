package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.util.Hand;

/**
 * AUDIT: Player-State Anomaly (sub-tick client-state contradictions)
 *
 * <p>An anticheat and many gameplay plugins derive trust from client-reported state:
 * which slot you hold, whether you're flying, which hand you're using. Most reconcile
 * that state once per tick. This probe sends CONTRADICTORY or FASTER-THAN-A-TICK state
 * within a single tick to find the window where the server's view of you is undefined
 * and a check reads the wrong value.
 *
 * <p>Modes (each a distinct, real non-op vector):
 * <ul>
 *   <li>{@code CONFLICT_FLYING} — claim flying and not-flying in the same tick via
 *       {@link UpdatePlayerAbilitiesC2SPacket}. A hardened server IGNORES client abilities
 *       entirely (server is authoritative) and re-sends {@link PlayerAbilitiesS2CPacket};
 *       a vulnerable one lets the last-seen flag win, opening a fly window. (Distinct from
 *       flight-claim, which just asserts flight; this asserts a contradiction.)</li>
 *   <li>{@code FAST_SLOT_SWAP} — change the held hotbar slot several times in one tick.
 *       Between the swaps, what item does the server think you hold? Plugins that read the
 *       held item on an interaction can be handed a slot you already left.</li>
 *   <li>{@code DUAL_HAND_SWING} — swing MAIN_HAND and OFF_HAND in the same tick. Tests
 *       whether attack/animation handlers assume one swing per tick.</li>
 *   <li>{@code USE_ITEM_TRUST} — switch to an empty (or different) slot and, in the SAME
 *       tick, send {@link PlayerInteractItemC2SPacket} for the main hand. The server reads
 *       the item from the slot; if it instead trusts the ACTION, you get a use (eat/pearl/
 *       bow) of an item you are not holding. This is the sharpest server-trust probe here.</li>
 * </ul>
 *
 * <p>Patch signal: treat the server's copy of held-slot / abilities / hand as authoritative
 * and reconcile it to a single value per tick BEFORE any check or plugin reads it; never let
 * an intra-tick sequence of client state decide a gameplay outcome.
 *
 * <p>Sprint/sneak/full-input flooding lives in {@code input-toggle-desync} — not duplicated
 * here. Non-op, client-side, no items required (USE_ITEM_TRUST works best with a throwable/
 * edible in another slot to make the effect visible). Own server only.
 */
public class PlayerStateAnomaly extends Module {
    public enum Mode { CONFLICT_FLYING, FAST_SLOT_SWAP, DUAL_HAND_SWING, USE_ITEM_TRUST }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which sub-tick state contradiction to send.")
        .defaultValue(Mode.USE_ITEM_TRUST).build());
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("actions-per-tick").description("How many times to run the chosen contradiction per active tick.")
        .defaultValue(1).range(1, 40).sliderRange(1, 10).build());
    private final Setting<Integer> useSlot = sgGeneral.add(new IntSetting.Builder()
        .name("use-slot").description("USE_ITEM_TRUST / FAST_SLOT_SWAP: hotbar slot to switch to (pick an EMPTY slot to prove slot-trust).")
        .defaultValue(8).range(0, 8).sliderRange(0, 8)
        .visible(() -> mode.get() == Mode.USE_ITEM_TRUST || mode.get() == Mode.FAST_SLOT_SWAP).build());
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable-on-kick").description("Disable if the server kicks (a kick here = it defended).")
        .defaultValue(true).build());

    private int sequence = 0, packetsSent = 0, abilitiesCorrections = 0, originalSlot = 0;

    public PlayerStateAnomaly() {
        super(AddonTemplate.TESTING_CATEGORY, "player-state-anomaly",
            "Sends sub-tick contradictory client state (flying, held slot, hand, use-item vs slot) to find where the server's view of you is momentarily undefined.");
    }

    @Override
    public void onActivate() {
        packetsSent = abilitiesCorrections = 0;
        if (mc.player != null) originalSlot = mc.player.getInventory().getSelectedSlot();
        info("Mode %s — watch: %s", mode.get(), watchHint());
    }

    private String watchHint() {
        return switch (mode.get()) {
            case CONFLICT_FLYING -> "a PlayerAbilities correction from the server = it stayed authoritative (GOOD); silent acceptance + a fly window = VULNERABLE.";
            case FAST_SLOT_SWAP -> "any interaction/plugin that acts on a slot you already left this tick.";
            case DUAL_HAND_SWING -> "double attack/animation processing, or an offhand action firing alongside mainhand.";
            case USE_ITEM_TRUST -> "an eat/throw/use effect for an item you are NOT holding = server trusts the action over the slot (VULNERABLE).";
        };
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        for (int i = 0; i < perTick.get(); i++) fireOnce();
    }

    private void fireOnce() {
        var nh = mc.player.networkHandler;
        switch (mode.get()) {
            case CONFLICT_FLYING -> {
                nh.sendPacket(new UpdatePlayerAbilitiesC2SPacket(abilities(true)));
                nh.sendPacket(new UpdatePlayerAbilitiesC2SPacket(abilities(false)));
                packetsSent += 2;
            }
            case FAST_SLOT_SWAP -> {
                // Cycle through several slots within the tick; the server should only ever
                // resolve ONE held item per tick.
                nh.sendPacket(new UpdateSelectedSlotC2SPacket(useSlot.get()));
                nh.sendPacket(new UpdateSelectedSlotC2SPacket((useSlot.get() + 4) % 9));
                nh.sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                packetsSent += 3;
            }
            case DUAL_HAND_SWING -> {
                nh.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
                nh.sendPacket(new HandSwingC2SPacket(Hand.OFF_HAND));
                packetsSent += 2;
            }
            case USE_ITEM_TRUST -> {
                // Switch to the (empty) probe slot, then in the SAME tick claim a main-hand
                // use. Does the server use the item from the slot (correct) or trust the action?
                nh.sendPacket(new UpdateSelectedSlotC2SPacket(useSlot.get()));
                nh.sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, ++sequence,
                    mc.player.getYaw(), mc.player.getPitch()));
                nh.sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                packetsSent += 3;
            }
        }
    }

    private PlayerAbilities abilities(boolean flying) {
        PlayerAbilities a = new PlayerAbilities();
        a.allowFlying = true;
        a.flying = flying;
        return a;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (mode.get() == Mode.CONFLICT_FLYING && event.packet instanceof PlayerAbilitiesS2CPacket) {
            abilitiesCorrections++;
        }
    }

    @EventHandler
    private void onGameLeft(meteordevelopment.meteorclient.events.game.GameLeftEvent event) {
        warning("Disconnected during player-state-anomaly — likely the server rejecting contradictory state (a PASS, not a crash).");
        if (autoDisable.get() && isActive()) toggle();
    }

    @Override
    public void onDeactivate() {
        info("Summary: %d packets sent.", packetsSent);
        if (mode.get() == Mode.CONFLICT_FLYING)
            info("Server ability corrections received: %d %s", abilitiesCorrections,
                abilitiesCorrections > 0 ? "(server stayed authoritative — GOOD)" : "(NONE — check whether a fly window opened)");
    }
}
