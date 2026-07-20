package net.xiaoyu233.fml.reload.transform.forge_compat.api;

import net.minecraft.world.biome.BiomeGenBase;

public interface IMixinWorldType {
    BiomeGenBase[] getBase12Biomes();
    void addNewBiome(BiomeGenBase biome);
    void removeBiome(BiomeGenBase biome);
}
