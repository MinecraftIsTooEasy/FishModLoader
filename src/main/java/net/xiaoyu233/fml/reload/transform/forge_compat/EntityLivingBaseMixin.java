package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.EntityDamageResult;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.Damage;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IForgeEntityDrops;
import net.xiaoyu233.fml.util.ReflectHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

@Mixin(EntityLivingBase.class)
public abstract class EntityLivingBaseMixin {
    @Shadow
    @org.spongepowered.asm.mixin.Final
    private HashMap activePotionsMap;
    @Shadow
    public int recentlyHit;
    // Declared on Entity; @Shadow resolves inherited members too.
    @Shadow
    public World worldObj;

    @Shadow
    protected abstract void onFinishedPotionEffect(PotionEffect par1PotionEffect);

    /**
     * onUpdate() -> HEAD, cancellable
     * if (ForgeHooks.onLivingUpdate(this)) return;
     */
    @Inject(method = "onUpdate()V",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnLivingUpdate(CallbackInfo ci) {
        if (ForgeHooks.onLivingUpdate(ReflectHelper.dyCast(this))) {
            ci.cancel();
        }
    }

    /**
     * attackEntityFrom(Damage) -> HEAD, cancellable
     * if (ForgeHooks.onLivingAttack(this, damage.getSource(), damage.getAmount())) return result;
     */
    @Inject(method = "attackEntityFrom(Lnet/minecraft/util/Damage;)Lnet/minecraft/entity/EntityDamageResult;",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnLivingAttack(Damage damage, CallbackInfoReturnable<Object> cir) {
        if (ForgeHooks.onLivingAttack(ReflectHelper.dyCast(this), damage.getSource(), damage.getAmount())) {
            cir.setReturnValue(null);
        }
    }

    /**
     * attackEntityFromHelper(Damage, EntityDamageResult) -> HEAD, cancellable
     * float amount1 = ForgeHooks.onLivingHurt(this, damage.getSource(), damage.getAmount());
     * if (amount1 <= 0) return result;
     */
    @Inject(method = "attackEntityFromHelper(Lnet/minecraft/util/Damage;Lnet/minecraft/entity/EntityDamageResult;)Lnet/minecraft/entity/EntityDamageResult;",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnLivingHurt(Damage damage, EntityDamageResult result, CallbackInfoReturnable<EntityDamageResult> cir) {
        float amount1 = ForgeHooks.onLivingHurt(ReflectHelper.dyCast(this), damage.getSource(), damage.getAmount());
        if (amount1 <= 0) {
            damage.setAmount(0.0F);
            cir.setReturnValue(result);
        }
    }

    /**
     * isOnLadder() -> HEAD, cancellable
     * Replaces the ladder/vine block check with ForgeHooks.isLivingOnLadder().
     */
    @Inject(method = "isOnLadder()Z",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeIsOnLadder(CallbackInfoReturnable<Boolean> cir) {
        EntityLivingBase self = ReflectHelper.dyCast(this);
        int i = MathHelper.floor_double(self.posX);
        int j = MathHelper.floor_double(self.boundingBox.minY);
        int k = MathHelper.floor_double(self.posZ);
        int l = self.worldObj.getBlockId(i, j, k);
        cir.setReturnValue(ForgeHooks.isLivingOnLadder(net.minecraft.block.Block.blocksList[l], self.worldObj, i, j, k, self));
    }

    /**
     * setRevengeTarget(EntityLivingBase) -> TAIL
     * Fires ForgeHooks.onLivingSetAttackTarget after the field is set.
     */
    @Inject(method = "setRevengeTarget(Lnet/minecraft/entity/EntityLivingBase;)V",
            at = @At("TAIL"))
    private void fmlForgeOnLivingSetAttackTarget(EntityLivingBase par1EntityLivingBase,
                                                  CallbackInfo ci) {
        ForgeHooks.onLivingSetAttackTarget(ReflectHelper.dyCast(this), par1EntityLivingBase);
    }

    /**
     * moveEntityWithHeading(float, float) -> inject after isAirBorne = true
     * Calls ForgeHooks.onLivingJump(this) when the entity becomes airborne.
     */
    @Inject(method = "moveEntityWithHeading(FF)V",
            at = @At(value = "FIELD",
                      target = "Lnet/minecraft/entity/Entity;isAirBorne:Z",
                      opcode = 181 /* Opcodes.PUTFIELD */,
                      shift = At.Shift.AFTER))
    private void fmlForgeOnLivingJump(float strafe, float forward, CallbackInfo ci) {
        ForgeHooks.onLivingJump(ReflectHelper.dyCast(this));
    }

    /**
     * fall(float) -> HEAD, cancellable
     * par1 = ForgeHooks.onLivingFall(this, par1);
     * if (par1 <= 0) return;
     */
    @Inject(method = "fall(F)V",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnLivingFall(float par1, CallbackInfo ci) {
        float modified = ForgeHooks.onLivingFall(ReflectHelper.dyCast(this), par1);
        if (modified <= 0) {
            ci.cancel();
        }
    }

    /**
     * onDeath(DamageSource) -> HEAD, cancellable
     * if (ForgeHooks.onLivingDeath(this, par1DamageSource)) return;
     */
    @Inject(method = "onDeath(Lnet/minecraft/util/DamageSource;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnLivingDeath(DamageSource par1DamageSource, CallbackInfo ci) {
        if (ForgeHooks.onLivingDeath(ReflectHelper.dyCast(this), par1DamageSource)) {
            ci.cancel();
        }
    }

    /**
     * onDeath(DamageSource) -> before dropFewItems
     * Sets up drop capturing so that onLivingDrops can modify drops.
     */
    @Inject(method = "onDeath(Lnet/minecraft/util/DamageSource;)V",
            at = @At(value = "INVOKE",
                      target = "Lnet/minecraft/entity/EntityLivingBase;dropFewItems(ZLnet/minecraft/util/DamageSource;)V",
                      shift = At.Shift.BEFORE))
    private void fmlForgeOnDeathCaptureDrops(DamageSource par1DamageSource, CallbackInfo ci) {
        IForgeEntityDrops drops = (IForgeEntityDrops) (Object) this;
        drops.fmlSetCapturingDrops(true);
        drops.fmlGetCapturedDrops().clear();
    }

    /**
     * onDeath(DamageSource) -> after dropEquipment
     * Releases captured drops and fires ForgeHooks.onLivingDrops.
     */
    @Inject(method = "onDeath(Lnet/minecraft/util/DamageSource;)V",
            at = @At(value = "INVOKE",
                      target = "Lnet/minecraft/entity/EntityLivingBase;dropEquipment(ZI)V",
                      shift = At.Shift.AFTER))
    private void fmlForgeOnDeathReleaseDrops(DamageSource par1DamageSource, CallbackInfo ci) {
        IForgeEntityDrops drops = (IForgeEntityDrops) (Object) this;
        drops.fmlSetCapturingDrops(false);
        if (!this.worldObj.isRemote) {
            if (!ForgeHooks.onLivingDrops(ReflectHelper.dyCast(this), par1DamageSource, drops.fmlGetCapturedDrops(), 0, this.recentlyHit > 0, 0)) {
                for (EntityItem item : drops.fmlGetCapturedDrops()) {
                    this.worldObj.spawnEntityInWorld(item);
                }
            }
        }
    }

    /**
     * Removes all potion effects that have curativeItem as a curative item for its effect.
     * Forge-added method.
     */
    @Unique
    public void curePotionEffects(ItemStack curativeItem) {
        if (this.worldObj.isRemote) {
            return;
        }
        // Remove all potion effects (Forge's default isCurativeItem returns true for all)
        this.activePotionsMap.clear();
    }

    /**
     * Returns true if the entity's rider (EntityPlayer) should face forward when mounted.
     * Forge-added method.
     */
    @Unique
    public boolean shouldRiderFaceForward(net.minecraft.entity.player.EntityPlayer player) {
        return ReflectHelper.dyCast(this) instanceof net.minecraft.entity.passive.EntityPig;
    }
}
