package net.xiaoyu233.fml.reload.transform.forge_compat.api;

import net.minecraft.world.gen.feature.WorldGenBigTree;

/** Provides cross-package access to BiomeGenBase's protected big-tree generator. */
public interface IBiomeBigTreeAccessor {
    WorldGenBigTree fmlGetWorldGeneratorBigTree();
}
