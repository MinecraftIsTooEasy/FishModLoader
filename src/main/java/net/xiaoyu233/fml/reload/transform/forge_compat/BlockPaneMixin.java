package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockPane;
import net.minecraft.util.EnumFace;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockPane.class)
public abstract class BlockPaneMixin {
    @Shadow
    public abstract boolean canThisPaneConnectToThisBlockID(int id);

    @Unique
    public boolean canPaneConnectTo(IBlockAccess access, int x, int y, int z, EnumFace dir) {
        int nx = dir.getNeighborX(x);
        int ny = dir.getNeighborY(y);
        int nz = dir.getNeighborZ(z);
        int neighborId = access.getBlockId(nx, ny, nz);
        if (canThisPaneConnectToThisBlockID(neighborId)) return true;
        Block neighborBlock = Block.blocksList[neighborId];
        if (neighborBlock == null) return false;
        int neighborMeta = access.getBlockMetadata(nx, ny, nz);
        return neighborBlock.isFaceFlatAndSolid(neighborMeta, dir.getOpposite());
    }
}
