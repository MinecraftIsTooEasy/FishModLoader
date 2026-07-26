package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockSkull;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;

@Mixin(BlockSkull.class)
public class BlockSkullMixin {
    @Unique
    public ArrayList<ItemStack> getBlockDropped(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> drops = new ArrayList<ItemStack>();
        if ((metadata & 8) == 0) {
            ItemStack itemstack = new ItemStack(Item.skull.itemID, 1, this.getDamageValue(world, x, y, z));
            TileEntitySkull tileentityskull = (TileEntitySkull) world.getBlockTileEntity(x, y, z);

            if (tileentityskull == null) {
                return drops;
            }
            if (tileentityskull.getSkullType() == 3 && tileentityskull.getExtraType() != null && tileentityskull.getExtraType().length() > 0) {
                itemstack.setTagCompound(new NBTTagCompound());
                itemstack.getTagCompound().setString("SkullOwner", tileentityskull.getExtraType());
            }
            drops.add(itemstack);
        }
        return drops;
    }

    @Shadow
    public int getDamageValue(World world, int x, int y, int z) {
        return 0;
    }
}
