package com.example.addon.mixin;

import com.example.addon.modules.antidupe.SlotOverlay;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the SlotOverlay module's slot ids at the tail of HandledScreen's
 * foreground draw. drawForeground runs in GUI-local coordinates, matching
 * Slot.x / Slot.y, so the ids land on their slots.
 */
@Mixin(HandledScreen.class)
public abstract class SlotOverlayMixin {

    @Shadow
    public abstract ScreenHandler getScreenHandler();

    @Inject(method = "drawForeground", at = @At("TAIL"))
    private void acaudit$drawSlotIds(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        SlotOverlay overlay = Modules.get().get(SlotOverlay.class);
        if (overlay == null || !overlay.isActive()) return;

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int color = overlay.getColorPacked();
        boolean shadow = overlay.shadow();

        for (Slot slot : getScreenHandler().slots) {
            context.drawText(tr, String.valueOf(slot.id), slot.x, slot.y, color, shadow);
        }
    }
}
