package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockFluid;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockFluid.class)
public abstract class BlockFluidMixin {
    /**
     * @reason Use waterColorMultiplier field instead of method call for Forge compat
     */
    @Overwrite
    public int colorMultiplier(IBlockAccess blockAccess, int x, int y, int z) {
        int l = 0;
        int i1 = 0;
        int j1 = 0;

        for (int k1 = -1; k1 <= 1; ++k1) {
            for (int l1 = -1; l1 <= 1; ++l1) {
                int i2 = blockAccess.getBiomeGenForCoords(x + l1, z + k1).waterColorMultiplier;
                l += (i2 & 16711680) >> 16;
                i1 += (i2 & 65280) >> 8;
                j1 += i2 & 255;
            }
        }

        return (l / 9 & 255) << 16 | (i1 / 9 & 255) << 8 | j1 / 9 & 255;
    }

    @Unique
    @Deprecated
    public float getFilledPercentage(IBlockAccess world, int x, int y, int z) {
        return 1 - BlockFluid.getFluidHeightPercent(world.getBlockMetadata(x, y, z));
    }
}
