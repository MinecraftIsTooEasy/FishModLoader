package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.item.EntityMinecartContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.minecart.MinecartInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityMinecartContainer.class)
public abstract class EntityMinecartContainerMixin {
    @Inject(method = "interactFirst", at = @At("HEAD"), cancellable = true)
    private void fmlForgeInteractFirst(EntityPlayer par1EntityPlayer, CallbackInfoReturnable<Boolean> cir) {
        if (MinecraftForge.EVENT_BUS.post(new MinecartInteractEvent((EntityMinecartContainer)(Object)this, par1EntityPlayer))) {
            cir.setReturnValue(true);
        }
    }
}
