package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.gen.MapGenRavine;
import org.spongepowered.asm.mixin.Mixin;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMapGenBaseAccessor;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MapGenRavine.class)
public abstract class MapGenRavineMixin {

    @Unique
    protected boolean isOceanBlock(byte[] data, int index, int x, int y, int z, int chunkX, int chunkZ) {
        return data[index] == Block.waterMoving.blockID || data[index] == Block.waterStill.blockID;
    }

    @Unique
    private boolean isExceptionBiome(BiomeGenBase biome) {
        if (biome == BiomeGenBase.beach) return true;
        if (biome == BiomeGenBase.desert) return true;
        return false;
    }

    @Unique
    private boolean isTopBlock(byte[] data, int index, int x, int y, int z, int chunkX, int chunkZ) {
        BiomeGenBase biome = ((IMapGenBaseAccessor)this).fmlGetWorld().getBiomeGenForCoords(x + chunkX * 16, z + chunkZ * 16);
        return (isExceptionBiome(biome) ? data[index] == Block.grass.blockID : data[index] == biome.topBlock);
    }

    @Unique
    protected void digBlock(byte[] data, int index, int x, int y, int z, int chunkX, int chunkZ, boolean foundTop) {
        BiomeGenBase biome = ((IMapGenBaseAccessor)this).fmlGetWorld().getBiomeGenForCoords(x + chunkX * 16, z + chunkZ * 16);
        int top = (isExceptionBiome(biome) ? Block.grass.blockID : biome.topBlock);
        int filler = (isExceptionBiome(biome) ? Block.dirt.blockID : biome.fillerBlock);
        int block = data[index];

        if (block == Block.stone.blockID || block == filler || block == top) {
            if (y < 10) {
                data[index] = (byte) Block.lavaMoving.blockID;
            } else {
                data[index] = 0;
                if (foundTop && data[index - 1] == filler) {
                    data[index - 1] = (byte) top;
                }
            }
        }
    }
}
