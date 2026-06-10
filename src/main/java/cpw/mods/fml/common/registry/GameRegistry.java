package cpw.mods.fml.common.registry;

import cpw.mods.fml.common.IWorldGenerator;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.xiaoyu233.fml.FishModLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Forge 1.6.4 GameRegistry compatibility surface.
 *
 * <p>Routes registrations to the underlying Minecraft / MITE registries.
 * Methods that map directly to MITE APIs are wired up here; methods that
 * need cross-cutting infrastructure (e.g. world-gen weighting) are kept
 * minimal and will be deepened during stage 5/6.
 */
public class GameRegistry {

    /** Cache of TileEntity ids registered via {@link #registerTileEntity}. */
    private static final Map<String, Class<? extends TileEntity>> tileEntityRegistry = new HashMap<>();

    /** Mod-supplied world generators, keyed by the mod-supplied weight. */
    private static final Map<Integer, IWorldGenerator> worldGenerators = new HashMap<>();

    private GameRegistry() {}

    // -------------------------------------------------------------- Block / Item

    public static void registerBlock(Block block, String name) {
        registerBlock(block, ItemBlock.class, name);
    }

    public static void registerBlock(Block block, Class<? extends ItemBlock> itemClass, String name) {
        registerBlock(block, itemClass, name, "");
    }

    public static void registerBlock(Block block, Class<? extends ItemBlock> itemClass, String name, String modId) {
        // Block construction in 1.6.4 already inserts into Block.blocksList[id].
        // We just need to set the unlocalized name and (optionally) instantiate
        // the ItemBlock so it's registered as a placeable item.
        try {
            block.setUnlocalizedName(name);
            if (itemClass != null) {
                ItemBlock itemBlock = itemClass.getConstructor(int.class).newInstance(block.blockID - 256);
                itemBlock.setUnlocalizedName(name);
            }
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.error("registerBlock failed for {}", name, thrown);
        }
    }

    public static void registerItem(Item item, String name) {
        registerItem(item, name, "");
    }

    public static void registerItem(Item item, String name, String modId) {
        item.setUnlocalizedName(name);
    }

    // -------------------------------------------------------------- TileEntity

    public static void registerTileEntity(Class<? extends TileEntity> tileEntityClass, String id) {
        tileEntityRegistry.put(id, tileEntityClass);
        try {
            // 1.6.4 has TileEntity.addMapping(class, id). Call via reflection so the
            // shim still loads on a MITE build that renamed the method.
            TileEntity.class
                    .getDeclaredMethod("addMapping", Class.class, String.class)
                    .invoke(null, tileEntityClass, id);
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.warn("TileEntity.addMapping unavailable; cached only: {}", id, thrown);
        }
    }

    public static void registerTileEntityWithAlternatives(Class<? extends TileEntity> tileEntityClass,
                                                          String id,
                                                          String... alternatives) {
        registerTileEntity(tileEntityClass, id);
    }

    // -------------------------------------------------------------- Recipes

    public static void addRecipe(ItemStack output, Object... params) {
        // 1.6.4 MITE: public addRecipe takes (output, boolean, params).
        // Pass false (= regular crafting, not preserved) to match upstream Forge.
        CraftingManager.getInstance().addRecipe(output, false, params);
    }

    public static void addRecipe(IRecipe recipe) {
        CraftingManager.getInstance().getRecipeList().add(recipe);
    }

    public static void addShapelessRecipe(ItemStack output, Object... params) {
        CraftingManager.getInstance().addShapelessRecipe(output, params);
    }

    public static void addSmelting(ItemStack input, ItemStack output, float experience) {
        // MITE simplified addSmelting to (id, ItemStack); experience is dropped
        // for now. Stage 6 will add a hook for XP-on-smelt that mods rely on.
        FurnaceRecipes.smelting().addSmelting(input.itemID, output);
    }

    public static void addSmelting(int itemId, ItemStack output, float experience) {
        FurnaceRecipes.smelting().addSmelting(itemId, output);
    }

    // -------------------------------------------------------------- World gen

    public static void registerWorldGenerator(IWorldGenerator generator) {
        registerWorldGenerator(generator, 0);
    }

    public static void registerWorldGenerator(IWorldGenerator generator, int weight) {
        worldGenerators.put(weight, generator);
    }

    /**
     * Run all registered world generators against the given chunk. Called
     * from a chunk-populate hook in stage 5/6 — for now this is mainly here
     * so mods that invoke it directly don't crash.
     */
    public static void generateWorld(int chunkX, int chunkZ, World world,
                                     IChunkProvider chunkGenerator,
                                     IChunkProvider chunkProvider) {
        Random random = new Random(world.getSeed());
        long xSeedFactor = (random.nextLong() >> 2) + 1L;
        long zSeedFactor = (random.nextLong() >> 2) + 1L;
        long chunkSeed = (xSeedFactor * chunkX + zSeedFactor * chunkZ) ^ world.getSeed();
        for (IWorldGenerator generator : worldGenerators.values()) {
            random.setSeed(chunkSeed);
            generator.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        }
    }

    // -------------------------------------------------------------- Lookups

    public static String findUniqueIdentifierFor(Block block) {
        return block != null ? block.getUnlocalizedName() : null;
    }

    public static String findUniqueIdentifierFor(Item item) {
        return item != null ? item.getUnlocalizedName() : null;
    }
}
