package com.example.addon.mixin;

import com.example.addon.modules.movement.NoSlow;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes the item-use movement slowdown. In 1.21.11 the slowdown is applied in
 * ClientPlayerEntity.applyMovementSpeedFactors by multiplying the input vector
 * by getActiveItemSpeedMultiplier() (~0.2) while using an item. When NoSlow is
 * active we force that multiplier to 1.0 so no slowdown is applied.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class NoSlowMixin {

    @Inject(method = "getActiveItemSpeedMultiplier", at = @At("HEAD"), cancellable = true)
    private void removeItemUseSlowdown(CallbackInfoReturnable<Float> cir) {
        NoSlow noSlow = Modules.get().get(NoSlow.class);
        if (noSlow != null && noSlow.isActive()) cir.setReturnValue(1.0F);
    }
}
