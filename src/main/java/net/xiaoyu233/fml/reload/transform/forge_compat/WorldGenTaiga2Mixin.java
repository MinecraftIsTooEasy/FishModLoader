package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.gen.feature.WorldGenTaiga2;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WorldGenTaiga2.class)
public abstract class WorldGenTaiga2Mixin {
    // Patches modify the generate method inline
}
