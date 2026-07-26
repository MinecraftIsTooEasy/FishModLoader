package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.gen.feature.WorldGenTrees;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WorldGenTrees.class)
public abstract class WorldGenTreesMixin {
    // Patches modify the generate method inline, no new methods added
}
