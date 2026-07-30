package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockMushroom;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(BlockMushroom.class)
public class BlockMushroomMixin {
    /** NOTE: MITE removed the vanilla {@code canBlockStay} API entirely (it uses
     * isLegalAt/isLegalOn/onNotLegal instead), so this cannot be an @Overwrite --
     * mixin application would fail hard on the missing target. Kept as @Unique
     * until it is rewired to MITE's actual API. See PLAN.md.
     */
    @Unique
    public boolean canBlockStay(World world, int x, int y, int z) {
        if (y >= 0 && y < 256) {
            int l = world.getBlockId(x, y - 1, z);
            Block soil = Block.blocksList[l];
            return (l == Block.mycelium.blockID || world.getFullBlockLightValue(x, y, z) < 13) &&
                   (soil != null);
        }
        return false;
    }
}
