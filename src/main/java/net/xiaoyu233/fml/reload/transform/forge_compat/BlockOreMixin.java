package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockOre;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockOre.class)
public abstract class BlockOreMixin {
    @Unique
    public int getExpDrop(World world, int metadata, int fortune) {
        // MITE handles ore experience internally through Block.dropXpOnBlockBreak.
        // Keep this Forge-shaped compatibility hook inert to avoid duplicate XP.
        return 0;
    }
}
