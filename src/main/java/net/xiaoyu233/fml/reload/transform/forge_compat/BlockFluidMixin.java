package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockFluid;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockFluid.class)
public abstract class BlockFluidMixin {
    @Unique
    @Deprecated
    public float getFilledPercentage(IBlockAccess world, int x, int y, int z) {
        return 1 - BlockFluid.getFluidHeightPercent(world.getBlockMetadata(x, y, z));
    }
}
