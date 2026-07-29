package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.Event.Result;
import net.xiaoyu233.fml.util.ReflectHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityLiving.class)
public abstract class EntityLivingMixin {
    @Shadow public int despawn_counter;
    @Shadow private boolean persistenceRequired;

    @Shadow public abstract void setDead();

    @Inject(method = "setAttackTarget", at = @At("RETURN"))
    private void onSetAttackTarget(EntityLivingBase par1EntityLivingBase, CallbackInfo ci) {
        ForgeHooks.onLivingSetAttackTarget(ReflectHelper.dyCast(this), par1EntityLivingBase);
    }

    @Inject(method = "tryDespawnEntity()V", at = @At("HEAD"), cancellable = true)
    private void onDespawnEntity(CallbackInfo ci) {
        if (!this.persistenceRequired && (this.despawn_counter & 0x1F) == 0x1F) {
            Result result = ForgeEventFactory.canEntityDespawn(ReflectHelper.dyCast(this));
            if (result == Result.DENY) {
                this.despawn_counter = 0;
                ci.cancel();
            } else if (result == Result.ALLOW) {
                this.setDead();
                ci.cancel();
            }
        }
    }
}
