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

    // NOTE: Both @Inject targets (func_94580_a / placeBlockAt inside onItemUse)
    // do not exist in MITE's ItemBlock. MITE's placement flow is raycast-driven:
    // ItemBlock.onItemRightClick → EntityPlayer.tryPlaceHeldItemAsBlock.
    // The @Unique placeBlockAt shim above still exists for Forge mods that call it
    // directly, but it is not wired into MITE's block-placement chain yet.
    // TODO: redirect inside EntityPlayer.tryPlaceHeldItemAsBlock to call placeBlockAt.
}
