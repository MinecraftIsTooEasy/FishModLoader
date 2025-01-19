package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.Entity;
import net.minecraft.EntityItem;
import net.minecraft.World;
import net.xiaoyu233.fml.config.Configs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityItem.class)
public abstract class FixTNT extends Entity {
    @Unique private boolean isExploded;
    public FixTNT(World par1World) {
        super(par1World);
    }

    @Shadow public abstract void tryRemoveFromWorldUniques();

    @Inject(
            method = "handleExplosion(Lnet/minecraft/Explosion;)Z",
            cancellable = true,
            at = @At(
                    value = "INVOKE",
                    shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/EntityItem;calcExplosionForce(FD)F"
            )
    )
    private void injectCancelExplosionCopy(CallbackInfoReturnable<Boolean> callback) {
        if (Configs.Fix.TNTFix.get() && this.isExploded) {
            this.setDead();
            this.tryRemoveFromWorldUniques();
            callback.setReturnValue(true);
        }
    }

    @Redirect(
            method = "handleExplosion(Lnet/minecraft/Explosion;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/EntityItem;tryRemoveFromWorldUniques()V"
            ),
            require = 0
    )
    private void injectUpdateExploded(EntityItem caller) {
        this.isExploded = true;
        this.tryRemoveFromWorldUniques();
    }
}

