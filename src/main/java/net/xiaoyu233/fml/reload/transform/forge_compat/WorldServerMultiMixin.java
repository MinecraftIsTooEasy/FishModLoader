package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.WorldServerMulti;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldServerMulti.class)
public abstract class WorldServerMultiMixin {

    @Inject(method = "saveLevel", at = @At("HEAD"), cancellable = true)
    private void fmlForgeSaveLevel(CallbackInfo ci) {
        // Override: saves perWorldStorage instead of nothing
        ci.cancel();
    }
}
