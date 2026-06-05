package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AUDIT: Block-Update Race (world-state consistency probe — axis 3)
 *
 * Movement validation assumes a stable world. This fires a block edit and a move
 * on the SAME tick, forcing the server to decide whether to validate the move
 * against the OLD world or the NEW one:
 *
 *   PLACE_AND_ENTER — place a block at the crosshair, then immediately report a
 *                     move INTO the space it now occupies. If validated against
 *                     the pre-place world the move looks legal; against the
 *                     post-place world it is a clip into a solid block.
 *   BREAK_AND_STAND — break the block under the target, then report staying
 *                     "onGround" on it. If validated against the pre-break world
 *                     it looks supported; against the post-break world you are
 *                     standing on air.
 *
 *   What it exploits: the ordering ambiguity between a same-tick block edit and
 *     a move that depends on that block.
 *   Measurement AC: has no world model; passes.
 *   Physics AC: validates against whichever snapshot it picked — exploitable if
 *     it picks the convenient one or doesn't fence the edit against the move.
 *   Intent AC: notices the move only makes sense if the player KNEW the edit
 *     would land first — suspiciously perfect timing.
 *   Fix: validate movement against a single consistent world snapshot fenced
 *     against same-tick block edits by the same player; apply edits and then
 *     re-validate pending moves.
 *
 * Look at the target block, press the key. Run against your OWN server only.
 */
public class BlockUpdateRace extends Module {
    public enum Mode { PLACE_AND_ENTER, BREAK_AND_STAND }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Place-then-enter, or break-then-stand.")
        .defaultValue(Mode.PLACE_AND_ENTER).build()
    );
    private final Setting<Double> moveDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("move-distance").description("How far to report moving toward the edited block (b).")
        .defaultValue(0.6).range(0.0, 2.0).sliderRange(0.0, 1.5).build()
    );
    private final Setting<Keybind> key = sgGeneral.add(new KeybindSetting.Builder()
        .name("key").description("Fires the edit+move race (look at the target block first).")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_4)).build()
    );

    private final PhysicsSequencer seq = new PhysicsSequencer(sgGeneral);
    private final MovementObserver obs = new MovementObserver(sgGeneral);

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
    private int seqNum = 0;

    public BlockUpdateRace() {
        super(AddonTemplate.MOVEMENT_CATEGORY, "block-update-race",
            "Fires a block place/break and a move on the same tick. Tests whether movement validates against a consistent world snapshot.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; wasPressed = false; seqNum = 0;
        obs.onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();

        boolean p = key.get().isPressed();
        boolean fire = p && !wasPressed;
        wasPressed = p;
        if (!fire) return;

        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            warning("Look at a block first.");
            return;
        }
        BlockPos pos = hit.getBlockPos();
        Direction side = hit.getSide();

        double yaw = Math.toRadians(mc.player.getYaw());
        double dirX = -Math.sin(yaw), dirZ = Math.cos(yaw);
        double d = moveDistance.get();

        if (mode.get() == Mode.PLACE_AND_ENTER) {
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, seqNum++));
            packetsSent++;
            seq.begin();
            seq.step(dirX * d, 0, dirZ * d, true);   // report moving into the just-placed block's space
            packetsSent++;
        } else {
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, side));
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, side));
            packetsSent += 2;
            seq.begin();
            seq.step(0, 0, 0, true);                 // report staying grounded on the now-broken block
            packetsSent++;
        }
        obs.markSent();
        info("Fired %s at %s.", mode.get(), pos.toShortString());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.onReceive(event.packet); }

    @EventHandler
    private void onSendSuppress(PacketEvent.Send event) { if (seq.filterSend(event.packet)) event.cancel(); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
