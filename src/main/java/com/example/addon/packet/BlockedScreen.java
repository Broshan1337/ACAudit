package com.example.addon.packet;

import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;

import java.util.Map;

/** Manage the inspector's blocked packet types (cancelled in Send/Receive while active). */
public class BlockedScreen extends WindowScreen {
    public BlockedScreen() { super(GuiThemes.get(), "Blocked Packet Types"); }

    @Override
    public void initWidgets() {
        Map<String, String> blocked = PacketBlock.snapshot();
        add(theme.label(blocked.isEmpty()
            ? "No types blocked. Open a packet and press 'Block this type' to add one."
            : blocked.size() + " type(s) blocked — these are dropped (not sent / not processed) while packet-inspector is on."));

        if (!blocked.isEmpty()) {
            WTable t = add(theme.table()).expandX().widget();
            for (Map.Entry<String, String> e : blocked.entrySet()) {
                t.add(theme.label(e.getValue()));
                WButton un = t.add(theme.button("Unblock")).widget();
                un.action = () -> { PacketBlock.unblock(e.getKey()); reload(); };
                t.row();
            }
            WButton clear = add(theme.button("Clear all")).widget();
            clear.action = () -> { PacketBlock.clear(); reload(); };
        }

        WButton back = add(theme.button("Back to inspector")).widget();
        back.action = () -> MinecraftClient.getInstance().setScreen(new PacketInspectorScreen());
    }
}
