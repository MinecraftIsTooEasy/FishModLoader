package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.passive.EntityOcelot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityOcelot.class)
public abstract class EntityOcelotMixin {
    @Inject(method = "isSuitablePlaceToSpawn", at = @At("HEAD"))
    private void fmlForgeIsSuitablePlaceToSpawn(CallbackInfoReturnable<Boolean> cir) {
        // Forge patch: replace Block.leaves check with block.isLeaves()
        // MITE may use a different check
    }
}
