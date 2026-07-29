package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockTorch;
import net.minecraft.util.EnumFace;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockTorch.class)
public abstract class BlockTorchMixin {

    @Unique
    private static boolean isBlockSolidOnSide(World world, int x, int y, int z, EnumFace face) {
        int id = world.getBlockId(x, y, z);
        Block block = Block.blocksList[id];
        if (block == null) return false;
        int meta = world.getBlockMetadata(x, y, z);
        return block.isFaceFlatAndSolid(meta, face);
    }

    @Shadow
    public abstract boolean canPlaceTorchOn(World world, int x, int y, int z);

    @Unique
    public boolean canPlaceBlockAt(World world, int x, int y, int z) {
        return isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST) ||
               isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST) ||
               isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH) ||
               isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH) ||
               canPlaceTorchOn(world, x, y - 1, z);
    }

    @Unique
    public int onBlockPlaced(World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ, int meta) {
        int j1 = meta;

        if (side == 2 && isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH)) j1 = 4;
        if (side == 3 && isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH)) j1 = 3;
        if (side == 4 && isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST)) j1 = 2;
        if (side == 5 && isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST)) j1 = 1;

        return j1;
    }

    @Shadow
    public void dropBlockAsItem(World world, int x, int y, int z, int meta, int fortune) {}

    @Unique
    public boolean canBlockStay(World world, int x, int y, int z) {
        if (world.getBlockMetadata(x, y, z) == 0) {
            if (isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST)) world.setBlockMetadata(x, y, z, 1, 2);
            else if (isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST)) world.setBlockMetadata(x, y, z, 2, 2);
            else if (isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH)) world.setBlockMetadata(x, y, z, 3, 2);
            else if (isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH)) world.setBlockMetadata(x, y, z, 4, 2);
        }

        int i1 = world.getBlockMetadata(x, y, z);
        boolean flag = false;

        if (!isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST) && i1 == 1) flag = true;
        if (!isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST) && i1 == 2) flag = true;
        if (!isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH) && i1 == 3) flag = true;
        if (!isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH) && i1 == 4) flag = true;

        if (flag) {
            this.dropBlockAsItem(world, x, y, z, i1, 0);
            world.setBlockToAir(x, y, z);
            return false;
        }
        return true;
    }
}
