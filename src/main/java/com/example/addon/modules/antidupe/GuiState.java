package com.example.addon.modules.antidupe;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

/**
 * Shared precondition for "a real container / plugin GUI is open".
 *
 * <p>The trap this closes: {@code mc.player.currentScreenHandler} is <b>never</b>
 * null in normal play — when no container is open it points at the player's own
 * inventory handler (syncId 0). So the widespread guard
 * {@code currentScreenHandler == null} is effectively always false, and container
 * race modules that used it were firing ClickSlot packets against the player's own
 * inventory (syncId 0) even when no plugin GUI was open — producing meaningless
 * packets and false "SILENT-NO-ECHO" findings.
 *
 * <p>A server-opened container is assigned a non-zero syncId (vanilla cycles 1..100),
 * so {@code currentScreen instanceof HandledScreen} together with a non-zero syncId
 * distinguishes a genuine container/station/plugin GUI from the player's own
 * inventory screen (which is a HandledScreen but keeps syncId 0).
 *
 * <p>Modules that intentionally operate on the player's own inventory (bundle,
 * drop/pickup, inventory sweep, OOB-slot probing, post-close clicks) must NOT use
 * this — they are correct to run without a container open.
 */
public final class GuiState {
    private GuiState() {}

    /** True when a real container / station / plugin GUI is open (not the player's own inventory). */
    public static boolean containerOpen(MinecraftClient mc) {
        return mc.player != null
            && mc.currentScreen instanceof HandledScreen<?>
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0;
    }
}
