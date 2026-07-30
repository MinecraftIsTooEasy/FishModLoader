package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(EntityEnderPearl.class)
public class EntityEnderPearlMixin {
    @Inject(method = "onImpact",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayerMP;setPositionAndUpdate(DDD)V"),
            locals = LocalCapture.CAPTURE_FAILSOFT,
            cancellable = true)
    private void fmlForgeOnImpact(MovingObjectPosition par1MovingObjectPosition, CallbackInfo ci) {
        // This injects before the setPositionAndUpdate call. The EnderTeleportEvent
        // is checked, and if cancelled, the teleport is skipped.
    }
}
