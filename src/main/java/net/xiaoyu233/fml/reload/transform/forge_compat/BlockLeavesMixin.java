package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;

@Mixin(BlockLeaves.class)
public abstract class BlockLeavesMixin {
    @Unique
    public boolean isShearable(ItemStack item, World world, int x, int y, int z) {
        return true;
    }

    @Unique
    public ArrayList<ItemStack> onSheared(ItemStack item, World world, int x, int y, int z, int fortune) {
        ArrayList<ItemStack> ret = new ArrayList<ItemStack>();
        ret.add(new ItemStack(Block.leaves, 1, world.getBlockMetadata(x, y, z) & 3));
        return ret;
    }

    @Unique
    public void beginLeavesDecay(World world, int x, int y, int z) {
        world.setBlockMetadataWithNotify(x, y, z, world.getBlockMetadata(x, y, z) | 8, 4);
    }

    @Unique
    public boolean isLeaves(World world, int x, int y, int z) {
        return true;
    }
}
