package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerFlyableFallEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityPlayer.class)
public abstract class EntityPlayerEventsMixin {

    @Inject(method = "attackTargetEntityWithCurrentItem(Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnAttackTargetEntity(Entity target, CallbackInfo callbackInfo) {
        if (MinecraftForge.EVENT_BUS.post(new AttackEntityEvent((EntityPlayer) (Object) this, target))) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "fall(F)V", at = @At("HEAD"))
    private void fmlForgeOnFall(float distance, CallbackInfo callbackInfo) {
        MinecraftForge.EVENT_BUS.post(new PlayerFlyableFallEvent((EntityPlayer) (Object) this, distance));
    }
}
