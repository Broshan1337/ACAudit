package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * AUDIT: Uncertainty Farm (tolerance-budget spend probe)
 *
 * A simulation-based anticheat cannot predict every physics interaction to the
 * millimetre, so it carries a tolerance/uncertainty budget: when the player
 * legitimately does something hard to model (gets knocked back, bounces on slime,
 * slides on ice, is pushed by entities) the AC WIDENS the envelope of positions it
 * will accept for a short window. That is correct and necessary. The audit vector
 * is whether that widened tolerance is scoped to the source that earned it and
 * decays immediately, or whether it pools into general slack a cheat can spend.
 *
 * This module waits for a REAL uncertainty source to occur (it does not fabricate
 * one), then within the same short window adds a small horizontal delta — a "spend"
 * that is illegal on its own but small enough to hide inside the borrowed budget —
 * and grades via MovementObserver whether the server absorbs it silently or still
 * corrects it.
 *
 *   What it exploits: tolerance granted for source A being available to unrelated
 *     movement B in the same window.
 *   Measurement AC: has no such budget; either always strict or always loose.
 *   Simulation AC: vulnerable if the budget is global/persistent rather than
 *     source-scoped and per-tick decaying.
 *   Intent AC: ties each unit of tolerance to the specific interaction that
 *     justified it and revokes it the instant that interaction ends.
 *   Fix (what any well-implemented AC should do): scope every tolerance grant to
 *     its source, cap it, and decay it per tick — never let knockback slack pay for
 *     a speed delta.
 *
 * Run against your OWN server only.
 */
public class UncertaintyFarm extends Module {
    public enum Source { AUTO_PUSH, SLIME, ICE, KEYBIND }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Source> source = sgGeneral.add(new EnumSetting.Builder<Source>()
        .name("source").description("Which real uncertainty source opens the spend window. AUTO_PUSH = a detected external velocity jump (knockback/explosion/entity push).")
        .defaultValue(Source.AUTO_PUSH).build()
    );
    private final Setting<Double> pushThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("push-threshold").description("Horizontal velocity jump (b/t) that counts as an external push for AUTO_PUSH.")
        .defaultValue(0.15).range(0.05, 1.0).sliderRange(0.05, 0.5)
        .visible(() -> source.get() == Source.AUTO_PUSH).build()
    );
    private final Setting<Double> spendDelta = sgGeneral.add(new DoubleSetting.Builder()
        .name("spend-delta").description("Extra horizontal speed (b/t) added during the window — the illegal amount we try to hide inside the borrowed budget.")
        .defaultValue(0.08).range(0.0, 0.5).sliderRange(0.0, 0.2).build()
    );
    private final Setting<Integer> spendTicks = sgGeneral.add(new IntSetting.Builder()
        .name("spend-ticks").description("How many ticks after the source to keep spending the delta.")
        .defaultValue(4).range(1, 40).sliderRange(1, 20).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Opens a spend window manually (KEYBIND source).")
        .defaultValue(meteordevelopment.meteorclient.utils.misc.Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_7))
        .visible(() -> source.get() == Source.KEYBIND).build()
    );

    private final MovementObserver obs = new MovementObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.")
        .defaultValue(true).build()
    );

    private int ticksActive = 0, packetsSent = 0, windowsOpened = 0;
    private int spendLeft = 0;
    private Vec3d prevVel = Vec3d.ZERO;
    private boolean wasPressed = false;

    public UncertaintyFarm() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "uncertainty-farm",
            "Waits for a real uncertainty source (push/slime/ice), then spends a small illegal delta inside the borrowed tolerance window. Tests whether tolerance is source-scoped or pools into slack.");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; windowsOpened = 0; spendLeft = 0; prevVel = Vec3d.ZERO; wasPressed = false; obs.onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        Vec3d v = mc.player.getVelocity();
        boolean open = false;
        switch (source.get()) {
            case AUTO_PUSH -> {
                double dh = Math.hypot(v.x - prevVel.x, v.z - prevVel.z);
                if (dh >= pushThreshold.get()) open = true;
            }
            case SLIME -> open = blockBelowIsOneOf(Blocks.SLIME_BLOCK);
            case ICE   -> open = blockBelowIsOneOf(Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.FROSTED_ICE);
            case KEYBIND -> {
                boolean p = key.get().isPressed();
                if (p && !wasPressed) open = true;
                wasPressed = p;
            }
        }
        prevVel = v;

        if (open && spendLeft == 0) { spendLeft = spendTicks.get(); windowsOpened++; }

        if (spendLeft > 0) {
            spendLeft--;
            // Spend: add the illegal delta in the input/look direction on top of the legitimate velocity.
            float fwd = mc.player.forwardSpeed, side = mc.player.sidewaysSpeed;
            double f, s;
            if (fwd == 0 && side == 0) { f = 1; s = 0; } // no input: spend straight ahead
            else { double len = Math.sqrt(fwd * fwd + side * side); f = fwd / len; s = side / len; }
            double yaw = Math.toRadians(mc.player.getYaw());
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            double dx = (f * -sin + s * cos) * spendDelta.get();
            double dz = (f *  cos + s * sin) * spendDelta.get();
            mc.player.setVelocity(v.x + dx, v.y, v.z + dz);
            packetsSent++;
            obs.markSent();
        }
    }

    private boolean blockBelowIsOneOf(net.minecraft.block.Block... blocks) {
        if (mc.world == null || mc.player == null) return false;
        BlockPos below = mc.player.getBlockPos().down();
        var state = mc.world.getBlockState(below);
        for (var b : blocks) if (state.isOf(b)) return true;
        return false;
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d spends, %d windows opened.", ticksActive, packetsSent, windowsOpened);
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
