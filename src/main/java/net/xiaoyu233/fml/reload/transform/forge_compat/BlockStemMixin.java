package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockStem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;

@Mixin(BlockStem.class)
public abstract class BlockStemMixin {
    @Shadow
    public Block fruitType;

    @Unique
    public ArrayList<ItemStack> getBlockDropped(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> ret = new ArrayList<ItemStack>();

        for (int i = 0; i < 3; i++) {
            if (world.rand.nextInt(15) <= metadata) {
                ret.add(new ItemStack(fruitType == Block.pumpkin ? Item.pumpkinSeeds : Item.melonSeeds));
            }
        }

        return ret;
    }
}
