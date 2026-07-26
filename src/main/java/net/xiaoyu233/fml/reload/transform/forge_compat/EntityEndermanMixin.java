package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.monster.EntityEnderman;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityEnderman.class)
public class EntityEndermanMixin {
    @Inject(method = "teleportTo", at = @At("HEAD"), cancellable = true)
    private void fmlForgeTeleportTo(double par1, double par3, double par5, CallbackInfoReturnable<Boolean> cir) {
        EnderTeleportEvent event = new EnderTeleportEvent((EntityEnderman)(Object)this, par1, par3, par5, 0);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            cir.setReturnValue(false);
        }
    }
}
