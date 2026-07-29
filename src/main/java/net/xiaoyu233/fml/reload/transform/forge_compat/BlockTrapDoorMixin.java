package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.util.EnumFace;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockTrapDoor.class)
public class BlockTrapDoorMixin {
    // Mixin forbids the public static Forge field API on a mixin class. Keep
    // the state private so the placement hook remains active.
    @Unique
    private static boolean disableValidation = false;

    /**
     * NOTE: was @Shadow, but MITE has no isValidSupportBlock on BlockTrapDoor
     * and a @Shadow with a body is invalid anyway. Kept as a @Unique helper.
     */
    @Unique
    private static boolean isValidSupportBlock(int id) {
        if (id <= 0) return false;
        return true;
    }

    @Unique
    private static boolean isSolidOnTop(World world, int x, int y, int z) {
        int id = world.getBlockId(x, y, z);
        Block block = Block.blocksList[id];
        if (block == null) return false;
        int meta = world.getBlockMetadata(x, y, z);
        return block.isFaceFlatAndSolid(meta, EnumFace.TOP);
    }

    /**
     * @reason Add disableValidation support and isBlockSolidOnSide check.
     *
     * NOTE: MITE removed the vanilla canPlaceBlockOnSide API, so this cannot
     * be an @Overwrite -- mixin application would fail hard. See PLAN.md.
     */
    @Unique
    public boolean canPlaceBlockOnSide(World world, int x, int y, int z, int side) {
        if (disableValidation) return true;
        if (side == 0) return false;
        if (side == 1) return true;

        int j1 = x;
        int k1 = z;

        if (side == 2) k1++;
        if (side == 3) k1--;
        if (side == 4) j1--;
        if (side == 5) j1++;

        return isValidSupportBlock(world.getBlockId(j1, y, k1)) || isSolidOnTop(world, j1, y, k1);
    }
}
