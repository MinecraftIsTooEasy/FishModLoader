package net.minecraftforge.common;

import net.minecraft.item.ItemStack;
import net.minecraft.util.WeightedRandomChestContent;
import net.minecraftforge.oredict.OreDictionary;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Forge 1.6.4 ChestGenHooks — partial real implementation.
 *
 * <p>Each known chest category gets a {@link ChestGenHooks} populated from
 * the vanilla static {@code WeightedRandomChestContent[]} field for that
 * structure. The static fields are private on stock 1.6.4 MITE, and the
 * matching widening lines in {@code fishmodloader.accesswidener} only take
 * effect at class-load time; the compiler still sees them as private. We
 * therefore read them via reflection (which uses {@code setAccessible})
 * inside {@link #loadDefaults()}, mirroring what Forge upstream does.
 *
 * <p>Mods can read the merged loot table back through {@link #getItems(Random)}
 * and mutate the in-memory registry through {@link #addItem(WeightedRandomChestContent)}
 * / {@link #removeItem(ItemStack)}. The {@link OreDictionary#WILDCARD_VALUE}
 * sentinel is honoured on {@code removeItem} so mods can drop every
 * sub-variant of a single item id in one call.
 *
 * <p>Not yet wired: the vanilla structure generators still iterate over
 * their own private static arrays directly, so mod-added entries do not
 * yet reach generation. Hooking each generator is a separate per-structure
 * Mixin pass (deferred). The public API is real so mods that read/inspect
 * the loot tables — or mutate them via this class and pass the result back
 * through their own generation hooks — work today.
 */
public class ChestGenHooks {

    public static final String MINESHAFT_CORRIDOR       = "mineshaftCorridor";
    public static final String PYRAMID_DESERT_CHEST     = "pyramidDesertyChest";
    public static final String PYRAMID_JUNGLE_CHEST     = "pyramidJungleChest";
    public static final String PYRAMID_JUNGLE_DISPENSER = "pyramidJungleDispenser";
    public static final String STRONGHOLD_CORRIDOR      = "strongholdCorridor";
    public static final String STRONGHOLD_LIBRARY       = "strongholdLibrary";
    public static final String STRONGHOLD_CROSSING      = "strongholdCrossing";
    public static final String VILLAGE_BLACKSMITH       = "villageBlacksmith";
    public static final String BONUS_CHEST              = "bonusChest";
    public static final String DUNGEON_CHEST            = "dungeonChest";

    private static final Map<String, ChestGenHooks> chestInfo = new HashMap<>();
    private static boolean defaultsLoaded = false;

    private final String category;
    private final List<WeightedRandomChestContent> contents = new ArrayList<>();
    private int countMin;
    private int countMax;

    public ChestGenHooks(String category) {
        this.category = category;
    }

    public ChestGenHooks(String category, WeightedRandomChestContent[] base, int min, int max) {
        this(category);
        if (base != null) {
            Collections.addAll(this.contents, base);
        }
        this.countMin = min;
        this.countMax = max;
    }

    /**
     * Snapshot the vanilla static arrays once. Called lazily on first
     * {@link #getInfo(String)}. Defaults track the count ranges Forge
     * upstream picks for each category.
     */
    private static void loadDefaults() {
        if (defaultsLoaded) return;
        defaultsLoaded = true;

        snapshot(MINESHAFT_CORRIDOR,
                "net.minecraft.world.gen.structure.StructureMineshaftPieces",
                "mineshaftChestContents", 3, 7);
        snapshot(PYRAMID_DESERT_CHEST,
                "net.minecraft.world.gen.structure.ComponentScatteredFeatureDesertPyramid",
                "itemsToGenerateInTemple", 2, 7);
        snapshot(PYRAMID_JUNGLE_CHEST,
                "net.minecraft.world.gen.structure.ComponentScatteredFeatureJunglePyramid",
                "junglePyramidsChestContents", 2, 7);
        snapshot(PYRAMID_JUNGLE_DISPENSER,
                "net.minecraft.world.gen.structure.ComponentScatteredFeatureJunglePyramid",
                "junglePyramidsDispenserContents", 2, 2);
        snapshot(STRONGHOLD_CORRIDOR,
                "net.minecraft.world.gen.structure.ComponentStrongholdChestCorridor",
                "strongholdChestContents", 1, 5);
        snapshot(STRONGHOLD_LIBRARY,
                "net.minecraft.world.gen.structure.ComponentStrongholdLibrary",
                "strongholdLibraryChestContents", 1, 5);
        snapshot(STRONGHOLD_CROSSING,
                "net.minecraft.world.gen.structure.ComponentStrongholdRoomCrossing",
                "strongholdRoomCrossingChestContents", 1, 5);
        snapshot(VILLAGE_BLACKSMITH,
                "net.minecraft.world.gen.structure.ComponentVillageHouse2",
                "villageBlacksmithChestContents", 3, 9);
        snapshot(BONUS_CHEST,
                "net.minecraft.world.WorldServer",
                "bonusChestContent", 10, 10);

        // DUNGEON_CHEST has no static array on vanilla — WorldGenDungeons
        // builds it dynamically per-world. Keep an empty registry so mods
        // calling addItem on it still get a working in-memory list.
        chestInfo.computeIfAbsent(DUNGEON_CHEST, ChestGenHooks::new);
    }

    /**
     * Reflectively read {@code className.fieldName} as a
     * {@link WeightedRandomChestContent}[] and seed {@code category} with
     * its contents. Robust to AW not being applied yet, missing classes,
     * or unexpected field types — failure leaves the registry empty for
     * that category, which is the safest fallback.
     */
    private static void snapshot(String category, String className, String fieldName, int min, int max) {
        try {
            Class<?> cls = Class.forName(className);
            Field f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(null);
            if (value instanceof WeightedRandomChestContent[]) {
                chestInfo.put(category, new ChestGenHooks(category, (WeightedRandomChestContent[]) value, min, max));
                return;
            }
            cpw.mods.fml.common.FMLLog.warning(
                    "ChestGenHooks: %s.%s has unexpected type %s",
                    className, fieldName, (value == null ? "null" : value.getClass().getName()));
        } catch (Throwable thrown) {
            cpw.mods.fml.common.FMLLog.warning(
                    "ChestGenHooks: failed to snapshot %s.%s: %s",
                    className, fieldName, thrown);
        }
        // Fall back to an empty registry so mods can still register entries.
        chestInfo.put(category, new ChestGenHooks(category, null, min, max));
    }

    public static ChestGenHooks getInfo(String category) {
        if (!defaultsLoaded) loadDefaults();
        return chestInfo.computeIfAbsent(category, ChestGenHooks::new);
    }

    public static void addItem(String category, WeightedRandomChestContent item) {
        getInfo(category).addItem(item);
    }

    public static void removeItem(String category, ItemStack stack) {
        getInfo(category).removeItem(stack);
    }

    /**
     * Generate a stack list for {@code item} with size in [min, max]. Used
     * by mods that build their own loot tables on top of vanilla generation.
     */
    public static ItemStack[] generateStacks(Random rand, ItemStack item, int min, int max) {
        if (item == null) return new ItemStack[0];
        int count = (min >= max) ? min : min + rand.nextInt(max - min + 1);
        if (count <= 0) return new ItemStack[0];
        int maxPerStack = item.getMaxStackSize();
        List<ItemStack> result = new ArrayList<>();
        int remaining = count;
        while (remaining > 0) {
            int take = Math.min(remaining, maxPerStack);
            ItemStack copy = item.copy();
            copy.stackSize = take;
            result.add(copy);
            remaining -= take;
        }
        return result.toArray(new ItemStack[0]);
    }

    /** Convenience adapter — adds an entry to the dungeon-loot registry. */
    public static void addDungeonLoot(ChestGenHooks dungeon, ItemStack item, int weight, int min, int max) {
        if (item == null) return;
        WeightedRandomChestContent entry = new WeightedRandomChestContent(item, min, max, weight);
        if (dungeon != null) {
            dungeon.addItem(entry);
        } else {
            getInfo(DUNGEON_CHEST).addItem(entry);
        }
    }

    public void addItem(WeightedRandomChestContent item) {
        if (item != null) contents.add(item);
    }

    /**
     * Remove every entry whose stack matches {@code stack} by item id and
     * damage value. MITE follows the metadata-subtype convention: distinct
     * variants of the same item share an id and differ by damage. To remove
     * every sub-variant in one call, pass damage =
     * {@link OreDictionary#WILDCARD_VALUE}. NBT and stack size are ignored,
     * matching upstream behaviour.
     */
    public void removeItem(ItemStack stack) {
        if (stack == null) return;
        int targetId = stack.itemID;
        int targetDmg = stack.getItemDamage();
        boolean wildcard = (targetDmg == OreDictionary.WILDCARD_VALUE);
        contents.removeIf(entry -> entry.theItemId != null
                && entry.theItemId.itemID == targetId
                && (wildcard || entry.theItemId.getItemDamage() == targetDmg));
    }

    /** Snapshot of the current contents — useful for tests and inspection. */
    public WeightedRandomChestContent[] getItems(Random rand) {
        return contents.toArray(new WeightedRandomChestContent[0]);
    }

    public int getCount(Random rand) {
        if (countMax <= countMin) return countMin;
        return countMin + rand.nextInt(countMax - countMin + 1);
    }

    public int getMin() { return countMin; }

    public void setMin(int min) { this.countMin = min; }

    public int getMax() { return countMax; }

    public void setMax(int max) { this.countMax = max; }

    public String getCategory() { return category; }
}
