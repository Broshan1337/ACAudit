package com.example.addon.mixin;

import com.example.addon.modules.movement.Timer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * Applies the Timer module's multiplier to the number of game ticks the client
 * processes per render frame. beginRenderTick returns how many ticks to run;
 * we scale that by the configured multiplier.
 */
@Mixin(RenderTickCounter.Dynamic.class)
public abstract class TimerMixin {

    @Inject(method = "beginRenderTick(JZ)I", at = @At("RETURN"), cancellable = true)
    private void onBeginRenderTick(long timeMillis, boolean tick, CallbackInfoReturnable<Integer> cir) {
        Timer timer = Modules.get().get(Timer.class);
        if (timer == null || !timer.isActive()) return;

        float mult = timer.getMultiplier();
        if (mult == 1.0f) return;

        int base = cir.getReturnValueI();
        // Scale tick count, carrying fractional remainder across frames via rounding.
        int scaled = Math.round(base * mult);
        cir.setReturnValue(scaled);
    }
}
