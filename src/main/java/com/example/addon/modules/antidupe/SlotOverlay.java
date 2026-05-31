package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * AUDIT: Slot Overlay
 *
 * Draws each slot's network id on top of any open container GUI so you can map a
 * layout at a glance instead of hovering one slot at a time. The ids shown are
 * exactly the values to put into the dupe modules' slot settings. Rendering is
 * done by SlotOverlayMixin; this module just holds the toggle and style.
 */
public class SlotOverlay extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color").description("Text color of the slot ids.")
        .defaultValue(new SettingColor(255, 255, 0, 255))
        .build()
    );
    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow").description("Draw the ids with a drop shadow for readability.")
        .defaultValue(true).build()
    );

    public SlotOverlay() {
        super(AddonTemplate.DUPE_CATEGORY, "slot-overlay",
            "Renders each slot's network id over the open GUI. Maps container/plugin layouts for the dupe modules.");
    }

    public int getColorPacked() {
        Color c = color.get();
        return (c.a << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }

    public boolean shadow() { return shadow.get(); }
}
