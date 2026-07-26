package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Shadow public int itemDamage;
    @Shadow public int itemID;
    @Shadow public int stackSize;
    @Shadow public abstract Item getItem();
    @Shadow public abstract int getMaxStackSize();
    @Shadow public abstract int getItemDamage();
    @Shadow public abstract void setItemDamage(int par1);
    @Shadow public abstract int getMaxDamage();
    @Shadow public abstract boolean hasEffect();
    @Shadow public abstract boolean isItemStackDamageable();

    @Inject(method = "getMaxStackSize", at = @At("HEAD"), cancellable = true)
    private void fmlForgeGetMaxStackSize(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(this.getMaxStackSize());
    }

    @Inject(method = "isItemStackDamageable", at = @At("HEAD"), cancellable = true)
    private void fmlForgeIsItemStackDamageable(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.getItem().getMaxDamage((ItemStack)(Object)this) > 0);
    }

    @Inject(method = "isItemDamaged", at = @At("HEAD"), cancellable = true)
    private void fmlForgeIsItemDamaged(CallbackInfoReturnable<Boolean> cir) {
        boolean damaged = this.itemDamage > 0;
        cir.setReturnValue(this.isItemStackDamageable() && damaged);
    }

    @Inject(method = "getItemDamageForDisplay", at = @At("HEAD"), cancellable = true)
    private void fmlForgeGetItemDamageForDisplay(CallbackInfoReturnable<Integer> cir) {
        if (this.getItem() != null) {
            cir.setReturnValue(this.itemDamage);
        }
    }

    @Inject(method = "getItemDamage", at = @At("HEAD"), cancellable = true)
    private void fmlForgeGetItemDamage(CallbackInfoReturnable<Integer> cir) {
        if (this.getItem() != null) {
            cir.setReturnValue(this.getItemDamage());
        }
    }

    @Inject(method = "setItemDamage", at = @At("HEAD"), cancellable = true)
    private void fmlForgeSetItemDamage(int par1, CallbackInfo ci) {
        if (this.getItem() != null) {
            this.setItemDamage(par1);
            ci.cancel();
        }
    }

    @Inject(method = "getMaxDamage", at = @At("HEAD"), cancellable = true)
    private void fmlForgeGetMaxDamage(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(this.getMaxDamage());
    }

    @Inject(method = "attemptDamageItem", at = @At("HEAD"), cancellable = true)
    private void fmlForgeAttemptDamageItem(int par1, CallbackInfoReturnable<Boolean> cir) {
        int damage = this.getItemDamage() + par1;
        this.setItemDamage(damage);
        cir.setReturnValue(this.getItemDamage() > this.getMaxDamage());
    }

    @Inject(method = "canHarvestBlock", at = @At("HEAD"), cancellable = true)
    private void fmlForgeCanHarvestBlock(net.minecraft.block.Block par1Block, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void fmlForgeGetTooltip(EntityPlayer par1EntityPlayer, boolean par2, CallbackInfoReturnable<List> cir) {
        List list = cir.getReturnValue();
        ForgeEventFactory.onItemTooltip((ItemStack)(Object)this, par1EntityPlayer, list, par2);
        cir.setReturnValue(list);
    }

    @Inject(method = "hasEffect", at = @At("HEAD"), cancellable = true)
    private void fmlForgeHasEffect(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.hasEffect());
    }
}
