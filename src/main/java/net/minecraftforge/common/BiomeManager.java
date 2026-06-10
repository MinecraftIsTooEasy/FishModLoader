package net.minecraftforge.common;

import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.gen.structure.MapGenVillage;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge 1.6.4 BiomeManager.
 *
 * <p>Stock MITE exposes {@link MapGenVillage#villageSpawnBiomes} as a
 * {@code public static final List} (the reference is final but the list is
 * mutable), so the village-spawn add/remove paths can be wired directly.
 *
 * <p>Stronghold and ocean-spawn lists live in {@code MapGenStronghold} and
 * {@code WorldChunkManager} as private fields under different names on
 * MITE; we keep an in-memory mirror so mods at least see their entries
 * back when querying, but the entries do not affect generation until those
 * fields are widened in a later pass.
 */
public class BiomeManager {

    /**
     * Mirror of {@link MapGenVillage#villageSpawnBiomes}. Modifying this
     * list directly affects vanilla village biome selection.
     */
    @SuppressWarnings("unchecked")
    public static final List<BiomeGenBase> villageSpawnBiomes =
            (List<BiomeGenBase>) MapGenVillage.villageSpawnBiomes;

    /** Stub mirror — not yet wired to MapGenStronghold.allowedBiomeGenBases. */
    public static final List<BiomeGenBase> strongholdBiomes = new ArrayList<>();

    /** Stub mirror — not yet wired to WorldChunkManager.biomesToSpawnIn. */
    public static final List<BiomeGenBase> oceanGenBiomes = new ArrayList<>();

    public static void addVillageBiome(BiomeGenBase biome, boolean canSpawn) {
        if (biome == null || !canSpawn) return;
        if (!villageSpawnBiomes.contains(biome)) {
            villageSpawnBiomes.add(biome);
        }
    }

    public static void removeVillageBiome(BiomeGenBase biome) {
        villageSpawnBiomes.remove(biome);
    }

    public static void addStrongholdBiome(BiomeGenBase biome) {
        if (biome != null && !strongholdBiomes.contains(biome)) {
            strongholdBiomes.add(biome);
        }
    }

    public static void removeStrongholdBiome(BiomeGenBase biome) {
        strongholdBiomes.remove(biome);
    }

    public static void addSpawnBiome(BiomeGenBase biome) {
        if (biome != null && !oceanGenBiomes.contains(biome)) {
            oceanGenBiomes.add(biome);
        }
    }

    public static void removeSpawnBiome(BiomeGenBase biome) {
        oceanGenBiomes.remove(biome);
    }
}
