package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.gen.feature.WorldGenForest;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WorldGenForest.class)
public abstract class WorldGenForestMixin {
    // Patches modify the generate method inline
}
