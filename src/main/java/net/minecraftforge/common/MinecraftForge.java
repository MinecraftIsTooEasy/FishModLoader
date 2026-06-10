package net.minecraftforge.common;

import cpw.mods.fml.common.FMLLog;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.EventBus;
import net.minecraftforge.oredict.OreDictionary;

import java.util.*;

/**
 * Stub-friendly version of MinecraftForge core entry. The 3 EventBuses are
 * the only thing mods absolutely need at this stage; the initialize() method
 * does Forge-patched id-table fixups that don't apply on stock 1.6.4 MITE.
 */
public class MinecraftForge {

    public static final EventBus EVENT_BUS       = new EventBus();
    public static final EventBus TERRAIN_GEN_BUS = new EventBus();
    public static final EventBus ORE_GEN_BUS     = new EventBus();

    private static final Map<List<Object>, Integer> toolHarvestLevels = new HashMap<>();
    private static final Set<List<Object>> toolEffectiveness         = new HashSet<>();
    private static final ForgeInternalHandler INTERNAL_HANDLER = new ForgeInternalHandler();

    public static void addGrassPlant(Block block, int metadata, int weight) {
        ForgeHooks.grassList.add(new ForgeHooks.GrassEntry(block, metadata, weight));
    }

    public static void addGrassSeed(ItemStack seed, int weight) {
        ForgeHooks.seedList.add(new ForgeHooks.SeedEntry(seed, weight));
    }

    public static void setToolClass(Item tool, String toolClass, int harvestLevel) {
        ForgeHooks.toolClasses.put(tool, Arrays.asList(toolClass, harvestLevel));
    }

    public static void setBlockHarvestLevel(Block block, int metadata, String toolClass, int harvestLevel) {
        List<Object> key = Arrays.asList(block, metadata, toolClass);
        toolHarvestLevels.put(key, harvestLevel);
        toolEffectiveness.add(key);
    }

    public static void removeBlockEffectiveness(Block block, int metadata, String toolClass) {
        toolEffectiveness.remove(Arrays.asList(block, metadata, toolClass));
    }

    public static void setBlockHarvestLevel(Block block, String toolClass, int harvestLevel) {
        for (int metadata = 0; metadata < 16; metadata++) {
            setBlockHarvestLevel(block, metadata, toolClass, harvestLevel);
        }
    }

    public static int getBlockHarvestLevel(Block block, int metadata, String toolClass) {
        Integer h = toolHarvestLevels.get(Arrays.asList(block, metadata, toolClass));
        return h == null ? -1 : h;
    }

    /** Package access for {@link ForgeHooks}. */
    static boolean isToolEffectiveAgainst(Block block, int metadata, String toolClass) {
        return toolEffectiveness.contains(Arrays.asList(block, metadata, toolClass));
    }

    public static void removeBlockEffectiveness(Block block, String toolClass) {
        for (int metadata = 0; metadata < 16; metadata++) {
            removeBlockEffectiveness(block, metadata, toolClass);
        }
    }

    /**
     * Bring the Forge core into a usable state for any subsequently-loaded
     * mod. This is the first thing the Forge mod loader calls on its own
     * world, and Forge mods rely on certain side-effects:
     *
     * <ul>
     *   <li>The internal event handler is registered on {@link #EVENT_BUS}
     *       so default Forge entity bookkeeping (UUID assignment etc.) runs.
     *       Our handler is currently a no-op marker; future passes will
     *       attach real listeners to it.</li>
     *   <li>{@link OreDictionary} is touched so its static initializer
     *       runs before any mod tries to register an ore name.</li>
     * </ul>
     *
     * The Forge-patched fixups for {@code Block.blocksList} and
     * {@code EntityEnderman.carriableBlocks} from upstream don't apply on
     * stock 1.6.4 MITE and are intentionally skipped.
     */
    public static void initialize() {
        FMLLog.info("MinecraftForge initialized (FishModLoader Forge compat)");
        try {
            EVENT_BUS.register(INTERNAL_HANDLER);
        } catch (Throwable thrown) {
            FMLLog.warning("Failed to register ForgeInternalHandler: %s", thrown);
        }
        // Force OreDictionary class init.
        try { OreDictionary.getOreName(0); } catch (Throwable ignored) {}
        // Seed BiomeDictionary with vanilla biomes so isBiomeOfType works
        // before any mod calls registerBiomeType.
        try { BiomeDictionary.registerAllBiomes(); } catch (Throwable thrown) {
            FMLLog.warning("BiomeDictionary.registerAllBiomes failed: %s", thrown);
        }
    }

    public static String getBrandingVersion() {
        return "Minecraft Forge " + ForgeVersion.getVersion();
    }
}
