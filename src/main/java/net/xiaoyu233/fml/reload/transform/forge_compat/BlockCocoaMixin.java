package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockCocoa;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.Random;

@Mixin(BlockCocoa.class)
public class BlockCocoaMixin {

    @Unique
    public ArrayList<ItemStack> getBlockDropped(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> dropped = new ArrayList<ItemStack>();
        int j1 = BlockCocoa.func_72219_c(metadata);
        byte b0 = 1;

        if (j1 >= 2) {
            b0 = 3;
        }

        for (int k1 = 0; k1 < b0; ++k1) {
            dropped.add(new ItemStack(Item.dyePowder, 1, 3));
        }
        return dropped;
    }

    @Unique
    public int idDropped(int par1, java.util.Random par2Random, int par3) {
        return 0;
    }
}
