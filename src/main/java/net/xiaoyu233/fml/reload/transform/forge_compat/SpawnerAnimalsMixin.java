package net.xiaoyu233.fml.reload.transform.forge_compat;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(net.minecraft.world.SpawnerAnimals.class)
public class SpawnerAnimalsMixin {
    // The patch modifies the canCreatureTypeSpawnAtLocation method and the main spawn loop.
    // These are complex inline changes best handled by @Overwrite on specific methods.
}
