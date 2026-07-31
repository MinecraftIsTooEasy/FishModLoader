package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumFace;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockLadder.class)
public abstract class BlockLadderMixin {

    @Unique
    private static boolean isBlockSolidOnSide(World world, int x, int y, int z, EnumFace face) {
        int id = world.getBlockId(x, y, z);
        Block block = Block.blocksList[id];
        if (block == null) return false;
        int meta = world.getBlockMetadata(x, y, z);
        return block.isFaceFlatAndSolid(meta, face);
    }

    @Unique
    public boolean canPlaceBlockAt(World world, int x, int y, int z) {
        return isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST) ||
               isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST) ||
               isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH) ||
               isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH);
    }

    @Unique
    public int onBlockPlaced(World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ, int meta) {
        int j1 = meta;

        if ((j1 == 0 || side == 2) && isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH)) j1 = 2;
        if ((j1 == 0 || side == 3) && isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH)) j1 = 3;
        if ((j1 == 0 || side == 4) && isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST)) j1 = 4;
        if ((j1 == 0 || side == 5) && isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST)) j1 = 5;

        return j1;
    }

    @Overwrite
    public boolean onNotLegal(World world, int x, int y, int z, int metadata) { return false; }

    @Unique
    public boolean canBlockStay(World world, int x, int y, int z) {
        int i1 = world.getBlockMetadata(x, y, z);
        boolean flag = false;

        if (i1 == 2 && isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH)) flag = true;
        if (i1 == 3 && isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH)) flag = true;
        if (i1 == 4 && isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST)) flag = true;
        if (i1 == 5 && isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST)) flag = true;

        if (!flag) {
            this.onNotLegal(world, x, y, z, i1);
            return false;
        }
        return true;
    }

    @Unique
    public boolean isLadder(World world, int x, int y, int z, EntityLivingBase entity) {
        return true;
    }
}
