package net.xiaoyu233.fml.reload.transform.forge_compat;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(net.minecraft.world.storage.MapData.class)
public class MapDataMixin {
    // No specific forge patch changes needed beyond what WorldServer provides
}
