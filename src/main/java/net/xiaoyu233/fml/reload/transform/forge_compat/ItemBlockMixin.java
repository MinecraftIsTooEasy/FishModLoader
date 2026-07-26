package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemBlock.class)
public abstract class ItemBlockMixin {
    @Shadow private int blockID;

    @Unique
    public boolean placeBlockAt(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ, int metadata) {
        if (!world.setBlock(x, y, z, this.blockID, metadata, 3)) {
            return false;
        }

        if (world.getBlockId(x, y, z) == this.blockID) {
            // onBlockPlacedBy and onPostBlockPlaced removed - don't exist in MITE
        }

        return true;
    }

    @Inject(method = "onItemUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemBlock;func_94580_a(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;IIIIFFFFI)Z", shift = At.Shift.BEFORE))
    private void fmlForgeRedirectToPlaceBlockAt(ItemStack par1ItemStack, EntityPlayer par2EntityPlayer, World par3World, int par4, int par5, int par6, int par7, float par8, float par9, float par10, CallbackInfoReturnable<Boolean> cir) {
    }

    @Inject(method = "onItemUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemBlock;placeBlockAt(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;IIIIFFFFI)Z"), locals = org.spongepowered.asm.mixin.injection.callback.LocalCapture.CAPTURE_FAILSOFT)
    private void fmlForgeOnItemUsePlace(ItemStack par1ItemStack, EntityPlayer par2EntityPlayer, World par3World, int par4, int par5, int par6, int par7, float par8, float par9, float par10, CallbackInfoReturnable<Boolean> cir) {
    }
}
