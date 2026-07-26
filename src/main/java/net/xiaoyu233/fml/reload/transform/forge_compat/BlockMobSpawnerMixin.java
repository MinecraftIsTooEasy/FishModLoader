package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockMobSpawner;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockMobSpawner.class)
public abstract class BlockMobSpawnerMixin {
    @Unique
    public int getExpDrop(World world, int data, int enchantmentLevel) {
        return 15 + world.rand.nextInt(15) + world.rand.nextInt(15);
    }
}
