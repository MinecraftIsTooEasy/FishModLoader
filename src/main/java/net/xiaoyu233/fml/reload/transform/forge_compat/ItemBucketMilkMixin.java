package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.ItemBucketMilk;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemBucketMilk.class)
public class ItemBucketMilkMixin {
    @Redirect(method = "onItemRightClick",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/entity/player/EntityPlayer;clearActivePotions()V"))
    private void fmlForgeCurePotionEffects(net.minecraft.entity.player.EntityPlayer player, ItemStack itemStack) {
        player.clearActivePotions();
    }
}
