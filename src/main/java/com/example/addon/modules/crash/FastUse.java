package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Hand;

/**
 * AUDIT: Fast-Use (item-use cooldown test)
 *
 * Repeatedly triggers item use ("right-click") on the held item, faster than the
 * vanilla use/cooldown allows. Tests whether the server enforces use timing
 * server-side: eating, potion drinking, ender-pearl throws, bow draw, shield
 * raise, etc. all have vanilla cooldowns or use durations a cheat tries to skip.
 *
 * WHAT THIS TESTS: not packet volume - it's whether your server re-derives the
 * minimum interval between uses from the item's use action and any cooldown, and
 * rejects/ignores uses that arrive too soon. A pearl-spam or instant-eat bypass
 * is the symptom of trusting client use timing.
 *
 * Patch signal: track last-use tick per (player, item) server-side; enforce the
 * item's use duration / ItemCooldownManager interval; ignore early uses rather
 * than processing them.
 */
public class FastUse extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> usesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("uses-per-tick")
        .description("Item-use attempts per tick. Start low to test cooldown enforcement.")
        .defaultValue(1).range(1, 100).sliderRange(1, 20).build()
    );
    private final Setting<Boolean> offhand = sgGeneral.add(new BoolSetting.Builder()
        .name("offhand").description("Use the offhand item instead of mainhand.")
        .defaultValue(false).build()
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

    public FastUse() {
        super(AddonTemplate.CRASH_CATEGORY, "ac-fast-use",
            "Triggers item use faster than vanilla allows. Tests server-side use/cooldown enforcement (eat, pearl, potion).");
    }

    @Override
    public void onActivate() { ticksActive = 0; packetsSent = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticksActive++;
        if (mc.player == null || mc.interactionManager == null) return;
        Hand hand = offhand.get() ? Hand.OFF_HAND : Hand.MAIN_HAND;
        for (int i = 0; i < usesPerTick.get(); i++) {
            mc.interactionManager.interactItem(mc.player, hand);
        }
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
