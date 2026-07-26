package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockCrops;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;

@Mixin(BlockCrops.class)
public abstract class BlockCropsMixin {
    @Shadow
    public abstract net.minecraft.item.Item getSeedItem();

    @Unique
    public ArrayList<ItemStack> getBlockDropped(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> ret = new ArrayList<ItemStack>();

        if (metadata >= 7) {
            for (int n = 0; n < 3 + fortune; n++) {
                if (world.rand.nextInt(15) <= metadata) {
                    ret.add(new ItemStack(this.getSeedItem(), 1, 0));
                }
            }
        }

        return ret;
    }
}
