package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenBigTree;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(WorldGenBigTree.class)
public abstract class WorldGenBigTreeMixin {

    @Shadow
    protected World worldObj;

    @Shadow
    protected int[] basePos;

    @Shadow
    protected int heightLimit;
}
