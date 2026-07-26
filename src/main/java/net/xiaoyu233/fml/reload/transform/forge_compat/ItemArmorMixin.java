package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemArmor.class)
public abstract class ItemArmorMixin {

    /**
     * Redirects the setCurrentItemOrArmor call inside onItemRightClick
     * to use slot index + 1, fixing a vanilla bug.
     */
    @Redirect(
            method = "onItemRightClick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/EntityPlayer;setCurrentItemOrArmor(ILnet/minecraft/item/ItemStack;)V")
    )
    private void fmlForgeRedirectSetCurrentItemOrArmor(EntityPlayer player,
                                                        int slot,
                                                        ItemStack stack) {
        player.setCurrentItemOrArmor(slot + 1, stack);
    }
}
