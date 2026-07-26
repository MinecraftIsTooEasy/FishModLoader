package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.village.VillageCollection;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(VillageCollection.class)
public class VillageCollectionMixin {
    // The patch changes int k = 32 + village.getVillageRadius() to float k = 32f + village.getVillageRadius()
    // This is a one-line type fix in the addDoorToVillage method.
}
