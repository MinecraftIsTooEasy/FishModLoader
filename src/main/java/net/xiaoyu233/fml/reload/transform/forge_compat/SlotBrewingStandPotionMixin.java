package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.Item;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net/minecraft/inventory/SlotBrewingStandPotion")
public abstract class SlotBrewingStandPotionMixin {

    @Inject(method = "canHoldPotion", at = @At("HEAD"), cancellable = true)
    private static void fmlForgeCanHoldPotion(ItemStack par0ItemStack, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(par0ItemStack != null && (par0ItemStack.getItem() instanceof ItemPotion || par0ItemStack.itemID == Item.glassBottle.itemID));
    }
}
