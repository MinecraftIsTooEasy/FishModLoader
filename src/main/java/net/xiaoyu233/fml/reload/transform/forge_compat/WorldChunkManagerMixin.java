package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.gen.layer.GenLayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.WorldTypeEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(WorldChunkManager.class)
public abstract class WorldChunkManagerMixin {

    @Unique
    private static java.util.ArrayList<BiomeGenBase> allowedBiomes = new java.util.ArrayList<>(
            java.util.Arrays.asList(BiomeGenBase.forest, BiomeGenBase.plains, BiomeGenBase.taiga,
                    BiomeGenBase.taigaHills, BiomeGenBase.forestHills, BiomeGenBase.jungle, BiomeGenBase.jungleHills));

    @Unique
    public GenLayer[] getModdedBiomeGenerators(WorldType worldType, long seed, GenLayer[] original) {
        WorldTypeEvent.InitBiomeGens event =
                new WorldTypeEvent.InitBiomeGens(worldType, seed, original);
        MinecraftForge.TERRAIN_GEN_BUS.post(event);
        return event.newBiomeGens;
    }
}
