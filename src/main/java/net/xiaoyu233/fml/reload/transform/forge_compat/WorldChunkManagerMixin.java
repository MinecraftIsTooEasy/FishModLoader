package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(net.minecraft.world.biome.WorldChunkManager.class)
public abstract class WorldChunkManagerMixin {

    @Unique
    private static java.util.ArrayList<BiomeGenBase> allowedBiomes = new java.util.ArrayList<BiomeGenBase>(
            java.util.Arrays.asList(BiomeGenBase.forest, BiomeGenBase.plains, BiomeGenBase.taiga,
                    BiomeGenBase.taigaHills, BiomeGenBase.forestHills, BiomeGenBase.jungle, BiomeGenBase.jungleHills));

    @Unique
    public net.minecraft.world.gen.layer.GenLayer[] getModdedBiomeGenerators(WorldType worldType, long seed, net.minecraft.world.gen.layer.GenLayer[] original) {
        net.minecraftforge.event.terraingen.WorldTypeEvent.InitBiomeGens event =
                new net.minecraftforge.event.terraingen.WorldTypeEvent.InitBiomeGens(worldType, seed, original);
        MinecraftForge.TERRAIN_GEN_BUS.post(event);
        return event.newBiomeGens;
    }
}
