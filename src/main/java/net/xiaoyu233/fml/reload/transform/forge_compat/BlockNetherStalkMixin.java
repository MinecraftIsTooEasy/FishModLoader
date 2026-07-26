package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockNetherStalk;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;

@Mixin(BlockNetherStalk.class)
public abstract class BlockNetherStalkMixin {
    @Unique
    public boolean canBlockStay(World world, int x, int y, int z) {
        int l = world.getBlockId(x, y - 1, z);
        return l == Block.slowSand.blockID;
    }

    @Unique
    public ArrayList<ItemStack> getBlockDropped(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> ret = new ArrayList<ItemStack>();
        int count = 1;

        if (metadata >= 3) {
            count = 2 + world.rand.nextInt(3) + (fortune > 0 ? world.rand.nextInt(fortune + 1) : 0);
        }

        for (int i = 0; i < count; i++) {
            ret.add(new ItemStack(Item.netherStalkSeeds));
        }

        return ret;
    }
}
