package net.minecraftforge.common;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.SaveHandler;

import java.io.File;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Forge 1.6.4 DimensionManager — adapted to MITE's WorldProvider line-up.
 *
 * <p>What works (stage 6a):
 * <ul>
 *   <li>Per-dim WorldProvider registration / lookup.</li>
 *   <li>Per-dim spawn-keep-loaded flag.</li>
 *   <li>Worldserver tracking and save-directory access (via the AW-widened
 *       {@code SaveHandler.getWorldDirectory()}).</li>
 *   <li>Default registrations for the three vanilla dimensions plus MITE's
 *       Underworld provider on dim id -2 (so mods see all of MITE's vanilla
 *       dimensions registered).</li>
 * </ul>
 *
 * <p>What's deferred (stage 6c+):
 * <ul>
 *   <li>Actually instantiating mod-registered worlds and inserting them into
 *       MinecraftServer.worldServers — needs a Mixin into the server's
 *       worldsLoaded path.</li>
 *   <li>Per-dim tick-time tracking (Forge's worldTickTimes table).</li>
 * </ul>
 */
public class DimensionManager {

    private static final Map<Integer, Class<? extends WorldProvider>> providers = new HashMap<>();
    private static final Map<Integer, Boolean> spawnSettings = new HashMap<>();
    private static final Hashtable<Integer, WorldServer> worlds = new Hashtable<>();
    private static final Map<Integer, Integer> dimensions = new HashMap<>();
    private static final Set<Integer> unloadQueue = new ConcurrentSkipListSet<>();
    @SuppressWarnings("unused")
    private static final Multiset<Integer> leakedWorlds = HashMultiset.create();

    public static boolean registerProviderType(int providerType, Class<? extends WorldProvider> provider, boolean keepLoaded) {
        if (providers.containsKey(providerType)) return false;
        providers.put(providerType, provider);
        spawnSettings.put(providerType, keepLoaded);
        return true;
    }

    public static void unregisterProviderType(int providerType) {
        providers.remove(providerType);
        spawnSettings.remove(providerType);
    }

    public static void init() {
        registerProviderType( 0, net.minecraft.world.WorldProviderSurface.class, true);
        registerProviderType(-1, net.minecraft.world.WorldProviderHell.class,    true);
        registerProviderType( 1, net.minecraft.world.WorldProviderEnd.class,    false);
        registerProviderType(-2, net.minecraft.world.WorldProviderUnderworld.class, false);

        registerDimension(  0,  0);
        registerDimension(-1, -1);
        registerDimension( 1,  1);
        registerDimension(-2, -2);
    }

    public static void registerDimension(int dimId, int providerType) {
        if (!providers.containsKey(providerType)) {
            throw new IllegalArgumentException("Unknown provider type " + providerType);
        }
        dimensions.put(dimId, providerType);
    }

    public static void unregisterDimension(int dimId) {
        dimensions.remove(dimId);
    }

    public static boolean isDimensionRegistered(int dimId) {
        return dimensions.containsKey(dimId);
    }

    public static int getProviderType(int dimId) {
        Integer providerType = dimensions.get(dimId);
        if (providerType == null) {
            throw new IllegalArgumentException("Dimension " + dimId + " not registered");
        }
        return providerType;
    }

    public static WorldProvider getProvider(int dimId) {
        try {
            return providers.get(getProviderType(dimId)).getDeclaredConstructor().newInstance();
        } catch (Exception cause) {
            throw new RuntimeException("Could not instantiate provider for dimension " + dimId, cause);
        }
    }

    public static Integer[] getIDs(boolean check) { return getIDs(); }

    public static Integer[] getIDs() {
        return worlds.keySet().toArray(new Integer[0]);
    }

    public static void setWorld(int dimId, WorldServer world) {
        if (world != null) worlds.put(dimId, world);
        else worlds.remove(dimId);
    }

    public static void initDimension(int dimId) {
        // Real impl: instantiate the WorldServer through MinecraftServer and
        // wire it into the server's worlds list. Deferred — see class javadoc.
    }

    public static WorldServer getWorld(int dimId) { return worlds.get(dimId); }

    public static WorldServer[] getWorlds() {
        return worlds.values().toArray(new WorldServer[0]);
    }

    public static boolean shouldLoadSpawn(int dimId) {
        Integer providerType = dimensions.get(dimId);
        return providerType != null && spawnSettings.getOrDefault(providerType, false);
    }

    public static int getNextFreeDimId() {
        int candidate = 2;
        while (dimensions.containsKey(candidate)) candidate++;
        return candidate;
    }

    public static void unloadWorld(int dimId) { unloadQueue.add(dimId); }

    public static void unloadWorlds(Hashtable<Integer, long[]> worldTickTimes) {
        // Per-dim tick-time tracking deferred to stage 6c.
    }

    public static int[] getStaticDimensionIDs() {
        Integer[] ids = getIDs();
        int[] out = new int[ids.length];
        for (int index = 0; index < ids.length; index++) out[index] = ids[index];
        return out;
    }

    /**
     * The save root directory for the currently-running server. Forge uses
     * this for chunk-loader configs etc. We rely on the AW-widened
     * {@code SaveHandler.getWorldDirectory()} so we don't need reflection.
     */
    public static File getCurrentSaveRootDirectory() {
        if (MinecraftServer.getServer() != null && MinecraftServer.getServer().worldServers != null) {
            for (WorldServer world : MinecraftServer.getServer().worldServers) {
                if (world == null) continue;
                ISaveHandler saveHandler = world.getSaveHandler();
                if (saveHandler instanceof SaveHandler) {
                    return ((SaveHandler) saveHandler).getWorldDirectory();
                }
            }
        }
        return null;
    }
}
