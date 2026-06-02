package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Riptide Launch (context-spoofed)
 *
 * A riptide trident grants a large velocity burst along the look vector — but
 * only when the player is in water or in rain. ACs commonly whitelist riptide
 * velocity too broadly, so a cheat can claim "riptide" to justify a big launch
 * while dry on open ground.
 *
 * Subtlety controls:
 *   power-jitter   — ±random variation on launch power. Tests variance-based vs.
 *                    threshold-based riptide speed detection.
 *   vector-noise   — rotate the look vector by ±N degrees before applying. Tests
 *                    whether the AC validates the exact direction or only magnitude.
 *   send-use-packet — send a PlayerInteractItemC2SPacket before the launch to
 *                    make the server's log show an item-use event, mimicking a
 *                    real riptide release. Tests context-packet cross-checking.
 *
 * DETECTION: when allowing a riptide velocity spike, the server must verify the
 * PRECONDITION it itself observed — the player was in water or exposed to rain,
 * and is holding a Riptide trident that was actually released.
 */
public class RiptideLaunch extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Double> power = sgGeneral.add(new DoubleSetting.Builder()
        .name("power").description("Launch magnitude in blocks per tick (Riptide III ~1.5).")
        .defaultValue(1.5).range(0.5, 10.0).sliderRange(0.5, 4.0).build()
    );
    private final Setting<Double> powerJitter = sgGeneral.add(new DoubleSetting.Builder()
        .name("power-jitter")
        .description("Random ±variation on launch power. Tests variance-based riptide speed detection.")
        .defaultValue(0.0).range(0.0, 0.3).sliderRange(0.0, 0.2).build()
    );
    private final Setting<Double> vectorNoise = sgGeneral.add(new DoubleSetting.Builder()
        .name("vector-noise")
        .description("Rotate the launch direction by ±N degrees. Tests direction-vs-magnitude validation.")
        .defaultValue(0.0).range(0.0, 5.0).sliderRange(0.0, 3.0).build()
    );
    private final Setting<Boolean> sendUsePacket = sgGeneral.add(new BoolSetting.Builder()
        .name("send-use-packet")
        .description("Send a PlayerInteractItemC2SPacket before launch to mimic a real riptide release.")
        .defaultValue(false).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Press to launch.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_X)).build()
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
    private boolean wasPressed = false;

    public RiptideLaunch() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "riptide-launch",
            "Imparts a riptide-magnitude launch with no water/rain context. Tests riptide precondition validation.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; wasPressed = false; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        double jit = powerJitter.get() > 0 ? (Math.random() * 2 - 1) * powerJitter.get() : 0;
        double sp = Math.max(0.1, power.get() + jit);

        Vec3d look;
        double noise = vectorNoise.get();
        if (noise > 0) {
            float pitch = (float)(mc.player.getPitch() + (Math.random() * 2 - 1) * noise);
            float yaw   = (float)(mc.player.getYaw()   + (Math.random() * 2 - 1) * noise);
            double pitchR = Math.toRadians(pitch), yawR = Math.toRadians(yaw);
            double cosPitch = Math.cos(pitchR);
            look = new Vec3d(-Math.sin(yawR) * cosPitch, -Math.sin(pitchR), Math.cos(yawR) * cosPitch);
        } else {
            look = mc.player.getRotationVector();
        }

        if (sendUsePacket.get()) {
            mc.player.networkHandler.sendPacket(new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND, 0, mc.player.getPitch(), mc.player.getYaw()));
            packetsSent++;
        }

        mc.player.setVelocity(look.x * sp, look.y * sp, look.z * sp);
        info("Launched (power %.2f), dry.", sp);
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisable.get() && isActive()) toggle();
    }
}
