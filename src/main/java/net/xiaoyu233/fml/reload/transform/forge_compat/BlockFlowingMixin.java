package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFlowing;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Random;

@Mixin(BlockFlowing.class)
public abstract class BlockFlowingMixin {
    /**
     * @reason Don't drop snow blocks when destroyed by lava (Forge compat)
     */
    @Overwrite
    public void updateTick(World world, int x, int y, int z, Random rand) {
        int l = rand.nextInt(3);
        int i1 = x;
        int j1 = y;
        int k1 = z;

        for (int l1 = 0; l1 < l; ++l1) {
            x += rand.nextInt(3) - 1;
            y += rand.nextInt(2) - 0;
            z += rand.nextInt(3) - 1;
        }

        int i2 = world.getBlockId(x, y, z);

        if (i2 != 0) {
            if (Block.blocksList[i2].blockMaterial == Block.blocksList[i2].blockMaterial) {
                this.triggerLavaMixEffects(world, x, y, z);
            } else if (i2 != Block.snow.blockID) {
                world.setBlockToAir(x, y, z);
            }
        }
    }

    @Shadow
    public abstract void triggerLavaMixEffects(World world, int x, int y, int z);
}
