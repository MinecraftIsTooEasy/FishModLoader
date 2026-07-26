package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.passive.EntityVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityVillager.class)
public class EntityVillagerMixin {
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void fmlForgeCheckSneaking(net.minecraft.entity.player.EntityPlayer par1EntityPlayer, CallbackInfoReturnable<Boolean> cir) {
        if (par1EntityPlayer.isSneaking()) {
            // Forge adds sneak check for villager trading
            // MITE may have different interact logic
        }
    }
}
