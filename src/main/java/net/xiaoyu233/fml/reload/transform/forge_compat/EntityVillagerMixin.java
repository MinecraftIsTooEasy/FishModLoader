package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityVillager.class)
public class EntityVillagerMixin {
    @Inject(method = "onEntityRightClicked", at = @At("HEAD"), cancellable = true)
    private void fmlForgeCheckSneaking(EntityPlayer par1EntityPlayer, ItemStack heldItem, CallbackInfoReturnable<Boolean> cir) {
        if (par1EntityPlayer.isSneaking()) {
            // Forge adds sneak check for villager trading
            // MITE may have different interact logic
            cir.setReturnValue(false);
        }
    }
}
