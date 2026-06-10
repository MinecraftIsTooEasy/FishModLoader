package cpw.mods.fml.common;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import java.util.Random;

/**
 * Stub for cpw.mods.fml.common.IWorldGenerator (Forge 1.6.4).
 * Implementors generate world content for a given chunk.
 */
public interface IWorldGenerator {
    void generate(Random random, int chunkX, int chunkZ, World world,
                  IChunkProvider chunkGenerator, IChunkProvider chunkProvider);
}
