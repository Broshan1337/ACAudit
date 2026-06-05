package com.example.addon.packet;

import com.example.addon.modules.testing.PacketInspector;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * The Packet Inspector screen: a filterable, color-coded live packet log.
 *
 * Top bar: pause/resume, clear, direction filter, type filter, sequence record/replay,
 * export. Below: the most recent packets (newest first), each a row you can open to
 * view all fields and — for C2S record packets — edit a field and replay it.
 */
public class PacketInspectorScreen extends WindowScreen {
    public enum Dir { BOTH, C2S, S2C }
    public enum Speed { REAL_TIME, FAST_4X, INSTANT, SLOW_2X }

    private static final Color OUT = new Color(90, 200, 130);
    private static final Color IN = new Color(95, 165, 235);
    private static final Color HEAD = new Color(170, 170, 170);
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final int MAX_ROWS = 300;

    private String filter = "";
    private Dir dir = Dir.BOTH;
    private boolean replayableOnly = false;   // show only outbound (C2S) packets we can send/replay
    private boolean editableOnly = false;     // show only outbound record packets whose fields we can edit
    private boolean group = false;            // collapse consecutive duplicate types into one row + count
    private Speed speed = Speed.REAL_TIME;
    private int tickCounter;

    public PacketInspectorScreen() {
        super(GuiThemes.get(), "Packet Inspector");
    }

    private PacketInspector module() { return Modules.get().get(PacketInspector.class); }

    @Override
    public void initWidgets() {
        PacketInspector mod = module();

        // ---- control bar ----
        WHorizontalList bar = add(theme.horizontalList()).expandX().widget();

        WButton pause = bar.add(theme.button(PacketLog.isPaused() ? "Resume" : "Pause")).widget();
        pause.action = () -> { PacketLog.setPaused(!PacketLog.isPaused()); reload(); };

        WButton clear = bar.add(theme.button("Clear")).widget();
        clear.action = () -> { PacketLog.clear(); reload(); };

        bar.add(theme.label("Dir:"));
        WDropdown<Dir> dirDd = bar.add(theme.dropdown(Dir.values(), dir)).widget();
        dirDd.action = () -> { dir = dirDd.get(); reload(); };

        bar.add(theme.label("mine only (C→S):"));
        WCheckbox mine = bar.add(theme.checkbox(replayableOnly)).widget();
        mine.action = () -> { replayableOnly = mine.checked; reload(); };

        bar.add(theme.label("editable only:"));
        WCheckbox edit = bar.add(theme.checkbox(editableOnly)).widget();
        edit.action = () -> { editableOnly = edit.checked; reload(); };

        bar.add(theme.label("group:"));
        WCheckbox grp = bar.add(theme.checkbox(group)).widget();
        grp.action = () -> { group = grp.checked; reload(); };

        WButton blocked = bar.add(theme.button("Blocked (" + PacketBlock.size() + ")")).widget();
        blocked.action = () -> MinecraftClient.getInstance().setScreen(new BlockedScreen());

        WButton script = bar.add(theme.button("Script")).widget();
        script.action = () -> MinecraftClient.getInstance().setScreen(new PacketScriptScreen());

        bar.add(theme.label("Filter:"));
        WTextBox filterBox = bar.add(theme.textBox(filter, "type name…")).minWidth(140).widget();
        filterBox.actionOnUnfocused = () -> { filter = filterBox.get(); reload(); };

        WButton export = bar.add(theme.button("Export")).widget();
        export.action = this::export;

        WButton refresh = bar.add(theme.button("Refresh")).widget();
        refresh.action = this::reload;

        // ---- sequence row ----
        WHorizontalList seq = add(theme.horizontalList()).expandX().widget();
        if (mod != null) {
            WButton rec = seq.add(theme.button(mod.isRecording() ? "Stop Rec (" + mod.sequenceSize() + ")" : "Record Seq")).widget();
            rec.action = () -> { if (mod.isRecording()) mod.stopRecording(); else mod.startRecording(); reload(); };

            seq.add(theme.label("Replay speed:"));
            WDropdown<Speed> spDd = seq.add(theme.dropdown(Speed.values(), speed)).widget();
            spDd.action = () -> speed = spDd.get();

            WButton replay = seq.add(theme.button("Replay Seq")).widget();
            replay.action = () -> mod.replaySequence(scaleOf(speed));
            WButton save = seq.add(theme.button("Save Seq")).widget();
            save.action = () -> MinecraftClient.getInstance().setScreen(new SequenceSaveScreen());
            WButton lib = seq.add(theme.button("Library")).widget();
            lib.action = () -> MinecraftClient.getInstance().setScreen(new SequenceLibraryScreen());
            seq.add(theme.label("(" + mod.sequenceSize() + " recorded, C2S only)"));
        }

        // ---- status ----
        add(theme.label(PacketLog.size() + " packets buffered" + (PacketLog.isPaused() ? " — PAUSED" : "")
            + ".  Click a row to view/edit/replay."));

        // ---- table ----
        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("time").color(HEAD));
        table.add(theme.label("dir").color(HEAD));
        table.add(theme.label("type").color(HEAD));
        table.add(theme.label("fields").color(HEAD));
        table.add(theme.label("").color(HEAD));
        table.row();

        List<PacketLog.Entry> all = PacketLog.snapshot();
        Collections.reverse(all); // newest first
        List<PacketLog.Entry> filtered = new java.util.ArrayList<>();
        for (PacketLog.Entry e : all) {
            if (replayableOnly && !e.outbound()) continue;
            if (editableOnly && !(e.outbound() && PacketDump.isEditable(e.packet()))) continue;
            if (dir == Dir.C2S && !e.outbound()) continue;
            if (dir == Dir.S2C && e.outbound()) continue;
            String type = PacketDump.displayName(e.packet(), e.outbound());
            if (!filter.isBlank() && !type.toLowerCase().contains(filter.toLowerCase())) continue;
            filtered.add(e);
        }

        int shown = 0;
        for (int i = 0; i < filtered.size() && shown < MAX_ROWS; ) {
            PacketLog.Entry e = filtered.get(i);
            String type = PacketDump.displayName(e.packet(), e.outbound());
            int count = 1;
            if (group) {   // collapse consecutive same-type rows into one
                while (i + count < filtered.size()
                    && PacketDump.displayName(filtered.get(i + count).packet(), filtered.get(i + count).outbound()).equals(type)
                    && filtered.get(i + count).outbound() == e.outbound()) count++;
            }
            table.add(theme.label(TIME.format(new Date(e.time()))).color(HEAD));
            table.add(theme.label(e.outbound() ? "C→S" : "S→C").color(e.outbound() ? OUT : IN));
            table.add(theme.label(count > 1 ? type + "  ×" + count : type));
            table.add(theme.label(shortFields(e.packet())));
            WButton view = table.add(theme.button(e.outbound() ? "edit" : "view")).widget();
            long id = e.id();
            view.action = () -> MinecraftClient.getInstance().setScreen(new PacketDetailScreen(id));
            table.row();
            shown++;
            i += count;
        }
        if (shown == 0) add(theme.label("(no packets match the current filter)"));
    }

    private static String shortFields(net.minecraft.network.packet.Packet<?> p) {
        List<PacketDump.FieldView> f = PacketDump.fields(p);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, f.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(f.get(i).name()).append('=').append(f.get(i).value());
        }
        String s = sb.toString();
        return s.length() > 70 ? s.substring(0, 67) + "..." : s;
    }

    private static double scaleOf(Speed s) {
        return switch (s) { case REAL_TIME -> 1.0; case FAST_4X -> 0.25; case INSTANT -> 0.0; case SLOW_2X -> 2.0; };
    }

    private void export() {
        try {
            File dir = new File(MinecraftClient.getInstance().runDirectory, "config/acaudit/reports");
            dir.mkdirs();
            File file = new File(dir, "packetlog_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".txt");
            List<PacketLog.Entry> all = PacketLog.snapshot();
            try (PrintWriter pw = new PrintWriter(file)) {
                pw.println("ACAudit packet log — " + new Date());
                for (PacketLog.Entry e : all) {
                    String type = PacketDump.displayName(e.packet(), e.outbound());
                    if (replayableOnly && !e.outbound()) continue;
                    if (editableOnly && !(e.outbound() && PacketDump.isEditable(e.packet()))) continue;
                    if (this.dir == Dir.C2S && !e.outbound()) continue;
                    if (this.dir == Dir.S2C && e.outbound()) continue;
                    if (!filter.isBlank() && !type.toLowerCase().contains(filter.toLowerCase())) continue;
                    pw.printf("[%s] %s %s%n", TIME.format(new Date(e.time())), e.outbound() ? "C->S" : "S->C", type);
                    for (PacketDump.FieldView fv : PacketDump.fields(e.packet()))
                        pw.printf("        %s = %s%n", fv.name(), fv.value());
                }
            }
            add(theme.label("Exported to " + file.getName()));
        } catch (Exception ex) {
            add(theme.label("Export failed: " + ex.getMessage()));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!PacketLog.isPaused() && ++tickCounter >= 20) { tickCounter = 0; reload(); }
    }
}
