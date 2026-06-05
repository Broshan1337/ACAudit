package com.example.addon.packet;

import com.example.addon.modules.testing.PacketInspector;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.util.List;

/** Browse / load / delete / export / import saved packet sequences. */
public class SequenceLibraryScreen extends WindowScreen {
    private static final Color WARN = new Color(235, 180, 90);
    private PacketInspectorScreen.Speed speed = PacketInspectorScreen.Speed.REAL_TIME;
    private WTextBox importPath;
    private String status = "";

    public SequenceLibraryScreen() { super(GuiThemes.get(), "Packet Sequence Library"); }

    private static double scaleOf(PacketInspectorScreen.Speed s) {
        return switch (s) { case REAL_TIME -> 1.0; case FAST_4X -> 0.25; case INSTANT -> 0.0; case SLOW_2X -> 2.0; };
    }

    @Override
    public void initWidgets() {
        PacketInspector mod = Modules.get().get(PacketInspector.class);

        WHorizontalList top = add(theme.horizontalList()).widget();
        top.add(theme.label("Replay speed:"));
        WDropdown<PacketInspectorScreen.Speed> sp = top.add(theme.dropdown(PacketInspectorScreen.Speed.values(), speed)).widget();
        sp.action = () -> speed = sp.get();
        WButton back = top.add(theme.button("Back to inspector")).widget();
        back.action = () -> MinecraftClient.getInstance().setScreen(new PacketInspectorScreen());

        List<SequenceStore.SavedSequence> all = SequenceStore.list();
        add(theme.label(all.size() + " saved sequence(s) in config/acaudit/sequences/."));

        WTable t = add(theme.table()).expandX().widget();
        t.add(theme.label("name")); t.add(theme.label("date")); t.add(theme.label("mc"));
        t.add(theme.label("pkts")); t.add(theme.label("tags")); t.add(theme.label("")); t.row();
        String current = net.minecraft.SharedConstants.getGameVersion().name();
        for (SequenceStore.SavedSequence s : all) {
            t.add(theme.label(s.name));
            t.add(theme.label(s.created == null ? "" : s.created));
            WLabel mcv = theme.label(s.mcVersion == null ? "?" : s.mcVersion);
            if (s.mcVersion != null && !s.mcVersion.equals(current)) mcv.color(WARN);
            t.add(mcv);
            t.add(theme.label(String.valueOf(s.packets.size())));
            t.add(theme.label(s.tags == null ? "" : String.join(",", s.tags)));
            WHorizontalList row = t.add(theme.horizontalList()).widget();
            WButton load = row.add(theme.button("Load+Replay")).widget();
            load.action = () -> {
                if (mod == null) { status = "packet-inspector missing."; reload(); return; }
                SequenceStore.LoadResult r = SequenceStore.toPackets(s);
                mod.replaySaved(r.items(), scaleOf(speed));
                status = String.format("Replaying %d (%d wire, %d reflective, %d skipped)%s",
                    r.items().size(), r.wireUsed(), r.reflectiveUsed(), r.skipped(),
                    r.versionMismatch() ? " — WARNING: captured on " + r.savedVersion() + ", you are on " + current + "; fields may differ" : "");
                reload();
            };
            WButton info = row.add(theme.button("Info")).widget();
            info.action = () -> { status = describe(s); reload(); };
            WButton export = row.add(theme.button("Path")).widget();
            export.action = () -> { status = "Shareable file: " + SequenceStore.fileFor(s.name).getAbsolutePath(); reload(); };
            WButton del = row.add(theme.button("Delete")).widget();
            del.action = () -> { SequenceStore.delete(s.name); status = "Deleted " + s.name; reload(); };
            t.row();
        }

        add(theme.horizontalSeparator("Import"));
        WHorizontalList imp = add(theme.horizontalList()).widget();
        imp.add(theme.label("file path:"));
        importPath = imp.add(theme.textBox("")).minWidth(280).widget();
        WButton doImport = imp.add(theme.button("Import")).widget();
        doImport.action = () -> {
            File f = new File(importPath.get().trim());
            if (!f.exists()) { status = "File not found: " + f.getPath(); reload(); return; }
            SequenceStore.SavedSequence s = SequenceStore.importFile(f);
            status = s != null ? "Imported '" + s.name + "'." : "Import failed (bad format).";
            reload();
        };

        if (!status.isBlank()) add(theme.label(status));
    }

    private static String describe(SequenceStore.SavedSequence s) {
        StringBuilder sb = new StringBuilder(s.name).append(": ").append(s.description == null ? "" : s.description);
        if (s.platform != null && !s.platform.isBlank()) sb.append(" | platform: ").append(s.platform);
        if (s.detectedPlugins != null && !s.detectedPlugins.isEmpty()) sb.append(" | plugins: ").append(String.join(",", s.detectedPlugins));
        if (s.expectedVulnerable != null && !s.expectedVulnerable.isBlank()) sb.append(" | vuln: ").append(s.expectedVulnerable);
        if (s.expectedPatched != null && !s.expectedPatched.isBlank()) sb.append(" | patched: ").append(s.expectedPatched);
        return sb.toString();
    }
}
