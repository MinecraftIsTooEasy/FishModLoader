package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemDye;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.Event;
import net.minecraftforge.event.entity.player.BonemealEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;


@Mixin(ItemDye.class)
public abstract class ItemDyeMixin {


    /**
     * Forge's expanded bonemeal application method that fires
     * BonemealEvent for forge mod integration.
     */
    @Unique
    private static boolean applyBonemeal(ItemStack par0ItemStack, World par1World,
                                          int par2, int par3, int par4,
                                          EntityPlayer player) {
        int l = par1World.getBlockId(par2, par3, par4);

        BonemealEvent event = new BonemealEvent(
                player, par1World, l, par2, par3, par4);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            return false;
        }
        if (event.getResult() == Event.Result.ALLOW) {
            if (!par1World.isRemote) {
                par0ItemStack.stackSize--;
            }
            return true;
        }

        if (l == Block.sapling.blockID) {
            if (!par1World.isRemote) {
                ((net.minecraft.block.BlockSapling) Block.sapling).markOrGrowMarked(
                        par1World, par2, par3, par4, par1World.rand);
            }
            return true;
        }

        if (l == Block.mushroomBrown.blockID || l == Block.mushroomRed.blockID) {
            if (!par1World.isRemote) {
                ((net.minecraft.block.BlockMushroom) Block.blocksList[l])
                        .fertilizeMushroom(par1World, par2, par3, par4, par0ItemStack, player);
            }
            return true;
        }

        return false;
    }
}
