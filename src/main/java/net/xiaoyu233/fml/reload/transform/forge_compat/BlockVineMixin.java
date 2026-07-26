package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockVine;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;

@Mixin(BlockVine.class)
public abstract class BlockVineMixin {
    @Unique
    public boolean isShearable(ItemStack item, World world, int x, int y, int z) {
        return true;
    }

    @Unique
    public ArrayList<ItemStack> onSheared(ItemStack item, World world, int x, int y, int z, int fortune) {
        ArrayList<ItemStack> ret = new ArrayList<ItemStack>();
        ret.add(new ItemStack(Block.vine, 1, 0));
        return ret;
    }

    @Unique
    public boolean isLadder(World world, int x, int y, int z, EntityLivingBase entity) {
        return true;
    }
}
