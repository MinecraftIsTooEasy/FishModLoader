package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinWorldType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Mixin(WorldType.class)
public class WorldTypeMixin implements IMixinWorldType {
    @Unique
    protected BiomeGenBase[] forge_biomesForWorldType;

    @Override
    public BiomeGenBase[] getBase12Biomes() {
        return forge_biomesForWorldType;
    }

    @Override
    public void addNewBiome(BiomeGenBase biome) {
        if (forge_biomesForWorldType == null) {
            forge_biomesForWorldType = new BiomeGenBase[]{biome};
            return;
        }
        Set<BiomeGenBase> newBiomesForWorld = new LinkedHashSet<>(Arrays.asList(forge_biomesForWorldType));
        newBiomesForWorld.add(biome);
        forge_biomesForWorldType = newBiomesForWorld.toArray(new BiomeGenBase[0]);
    }

    @Override
    public void removeBiome(BiomeGenBase biome) {
        if (forge_biomesForWorldType == null) return;
        Set<BiomeGenBase> newBiomesForWorld = new LinkedHashSet<>(Arrays.asList(forge_biomesForWorldType));
        newBiomesForWorld.remove(biome);
        forge_biomesForWorldType = newBiomesForWorld.toArray(new BiomeGenBase[0]);
    }
}
