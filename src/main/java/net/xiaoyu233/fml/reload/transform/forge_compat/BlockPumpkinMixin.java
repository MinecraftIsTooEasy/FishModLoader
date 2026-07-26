package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockPumpkin;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockPumpkin.class)
public class BlockPumpkinMixin {
    /**
     * @reason Simplify canPlaceBlockAt for Forge compat.
     *
     * NOTE: MITE removed the vanilla canPlaceBlockAt API (it uses
     * isLegalAt/isLegalOn instead), so this cannot be an @Overwrite --
     * mixin application would fail hard. Kept inert. See PLAN.md.
     */
    @Unique
    public boolean canPlaceBlockAt(World world, int x, int y, int z) {
        return world.isAirBlock(x, y, z) && world.getBlockId(x, y - 1, z) != 0;
    }
}
