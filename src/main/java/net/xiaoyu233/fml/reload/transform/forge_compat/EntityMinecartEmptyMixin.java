package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.minecart.MinecartInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityMinecartEmpty.class)
public abstract class EntityMinecartEmptyMixin {
    @Inject(method = "onEntityRightClicked", at = @At("HEAD"), cancellable = true)
    private void fmlForgeInteractFirst(EntityPlayer par1EntityPlayer, ItemStack heldItem, CallbackInfoReturnable<Boolean> cir) {
        if (MinecraftForge.EVENT_BUS.post(new MinecartInteractEvent((EntityMinecartEmpty)(Object)this, par1EntityPlayer))) {
            cir.setReturnValue(true);
        }
    }
}
