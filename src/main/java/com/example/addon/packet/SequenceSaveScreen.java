package com.example.addon.packet;

import com.example.addon.modules.testing.PacketInspector;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/** Save the inspector's currently-recorded sequence to disk with self-documenting metadata. */
public class SequenceSaveScreen extends WindowScreen {
    private WTextBox name, desc, tags, vuln, patched;
    private String status = "";

    public SequenceSaveScreen() { super(GuiThemes.get(), "Save Packet Sequence"); }

    @Override
    public void initWidgets() {
        PacketInspector mod = Modules.get().get(PacketInspector.class);
        int count = mod != null ? mod.recordedSequence().size() : 0;
        add(theme.label(count + " packet(s) recorded. " + (count == 0 ? "Record a sequence first (Record Seq)." : "")));

        WTable t = add(theme.table()).widget();
        t.add(theme.label("name")); name = t.add(theme.textBox("")).minWidth(220).widget(); t.row();
        t.add(theme.label("description")); desc = t.add(theme.textBox("")).minWidth(220).widget(); t.row();
        t.add(theme.label("tags (comma)")); tags = t.add(theme.textBox("")).minWidth(220).widget(); t.row();
        t.add(theme.label("expected if vulnerable")); vuln = t.add(theme.textBox("")).minWidth(220).widget(); t.row();
        t.add(theme.label("expected if patched")); patched = t.add(theme.textBox("")).minWidth(220).widget(); t.row();

        WButton save = add(theme.button("Save")).widget();
        save.action = () -> {
            if (mod == null || mod.recordedSequence().isEmpty()) { status = "Nothing recorded."; reload(); return; }
            if (name.get().isBlank()) { status = "Enter a name."; reload(); return; }
            List<String> tagList = new ArrayList<>();
            for (String s : tags.get().split(",")) if (!s.isBlank()) tagList.add(s.trim());
            SequenceStore.SavedSequence seq = SequenceStore.build(name.get(), desc.get(), tagList,
                vuln.get(), patched.get(), mod.recordedSequence());
            boolean ok = SequenceStore.save(seq);
            status = ok ? "Saved " + seq.packets.size() + " packet(s) to " + SequenceStore.fileFor(name.get()).getName()
                        : "Save failed.";
            reload();
        };
        WButton back = add(theme.button("Back to inspector")).widget();
        back.action = () -> MinecraftClient.getInstance().setScreen(new PacketInspectorScreen());

        if (!status.isBlank()) add(theme.label(status));
    }
}
