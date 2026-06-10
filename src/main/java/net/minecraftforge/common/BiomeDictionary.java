package net.minecraftforge.common;

import net.minecraft.world.biome.BiomeGenBase;

import java.util.*;

/**
 * Forge 1.6.4 BiomeDictionary — partial real implementation.
 *
 * <p>Stock MITE exposes the same vanilla biome static fields Forge upstream
 * indexes ({@code BiomeGenBase.ocean}, {@code .desert}, etc.), so
 * {@link #registerAllBiomes()} seeds the type tables from those. Mods that
 * call {@link #isBiomeOfType} on vanilla biomes get the right answer
 * without further setup. Mod-added biomes still register through
 * {@link #registerBiomeType}.
 *
 * <p>Type assignments mirror Forge upstream's defaults for 1.6.4.
 */
public class BiomeDictionary {

    private static final Map<BiomeGenBase, EnumSet<Type>> biomeMap = new HashMap<>();
    private static final Map<Type, List<BiomeGenBase>> typeMap = new HashMap<>();
    private static boolean defaultsRegistered = false;

    public static void registerBiomeType(BiomeGenBase biome, Type... types) {
        if (biome == null) return;
        EnumSet<Type> typeSet = biomeMap.computeIfAbsent(biome, b -> EnumSet.noneOf(Type.class));
        typeSet.addAll(Arrays.asList(types));
        for (Type t : types) {
            typeMap.computeIfAbsent(t, k -> new ArrayList<>()).add(biome);
        }
    }

    public static boolean isBiomeRegistered(BiomeGenBase biome) {
        return biomeMap.containsKey(biome);
    }

    public static boolean isBiomeOfType(BiomeGenBase biome, Type type) {
        EnumSet<Type> set = biomeMap.get(biome);
        return set != null && set.contains(type);
    }

    public static BiomeGenBase[] getBiomesForType(Type type) {
        List<BiomeGenBase> list = typeMap.get(type);
        return list == null ? new BiomeGenBase[0] : list.toArray(new BiomeGenBase[0]);
    }

    public static Type[] getTypesForBiome(BiomeGenBase biome) {
        EnumSet<Type> set = biomeMap.get(biome);
        return set == null ? new Type[0] : set.toArray(new Type[0]);
    }

    /**
     * Register all stock 1.6.4 MITE biomes with the canonical Forge type
     * sets. Idempotent — safe to call multiple times.
     */
    public static void registerAllBiomes() {
        if (defaultsRegistered) return;
        defaultsRegistered = true;

        registerBiomeType(BiomeGenBase.ocean,            Type.WATER);
        registerBiomeType(BiomeGenBase.frozenOcean,      Type.WATER, Type.FROZEN);
        registerBiomeType(BiomeGenBase.river,            Type.WATER);
        registerBiomeType(BiomeGenBase.frozenRiver,      Type.WATER, Type.FROZEN);
        registerBiomeType(BiomeGenBase.desertRiver,      Type.WATER, Type.DESERT);
        registerBiomeType(BiomeGenBase.jungleRiver,      Type.WATER, Type.JUNGLE);
        registerBiomeType(BiomeGenBase.swampRiver,       Type.WATER, Type.SWAMP);
        registerBiomeType(BiomeGenBase.beach,            Type.BEACH);

        registerBiomeType(BiomeGenBase.plains,           Type.PLAINS);
        registerBiomeType(BiomeGenBase.icePlains,        Type.PLAINS, Type.FROZEN);
        registerBiomeType(BiomeGenBase.iceMountains,     Type.HILLS, Type.FROZEN);

        registerBiomeType(BiomeGenBase.desert,           Type.DESERT);
        registerBiomeType(BiomeGenBase.desertHills,      Type.DESERT, Type.HILLS);

        registerBiomeType(BiomeGenBase.forest,           Type.FOREST);
        registerBiomeType(BiomeGenBase.forestHills,      Type.FOREST, Type.HILLS);
        registerBiomeType(BiomeGenBase.taiga,            Type.FOREST, Type.FROZEN);
        registerBiomeType(BiomeGenBase.taigaHills,       Type.FOREST, Type.FROZEN, Type.HILLS);

        registerBiomeType(BiomeGenBase.extremeHills,     Type.MOUNTAIN, Type.HILLS);
        registerBiomeType(BiomeGenBase.extremeHillsEdge, Type.MOUNTAIN);

        registerBiomeType(BiomeGenBase.swampland,        Type.SWAMP);

        registerBiomeType(BiomeGenBase.jungle,           Type.JUNGLE);
        registerBiomeType(BiomeGenBase.jungleHills,      Type.JUNGLE, Type.HILLS);

        registerBiomeType(BiomeGenBase.hell,             Type.NETHER);
        registerBiomeType(BiomeGenBase.underworld,       Type.NETHER);
        registerBiomeType(BiomeGenBase.sky,              Type.END);
    }

    public static boolean areBiomesEquivalent(BiomeGenBase a, BiomeGenBase b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        EnumSet<Type> ta = biomeMap.get(a);
        EnumSet<Type> tb = biomeMap.get(b);
        return ta != null && tb != null && ta.equals(tb);
    }

    public static boolean areSimilar(BiomeGenBase a, BiomeGenBase b) {
        return areBiomesEquivalent(a, b);
    }

    public enum Type {
        FOREST, PLAINS, MOUNTAIN, HILLS, SWAMP, WATER, DESERT, FROZEN, JUNGLE,
        WASTELAND, BEACH, NETHER, END, MUSHROOM, MAGICAL
    }
}
