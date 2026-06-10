package net.minecraftforge.common;

import net.minecraft.item.ItemStack;
import net.minecraft.util.WeightedRandomChestContent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Forge 1.6.4 DungeonHooks.
 *
 * <p>Forge mods register extra dungeon spawner mobs and chest loot through
 * this class. We keep the in-memory registries here; the actual dungeon
 * generator picks them up via {@link #getRandomDungeonMob(Random)} /
 * {@link #getDungeonLoot()}. Tying the loot list back into the vanilla
 * dungeon loot table requires widening private fields on
 * {@code WorldGenDungeons}, which is deferred — until then the loot list
 * is queryable but the vanilla generator does not consult it.
 */
public class DungeonHooks {

    private static final List<DungeonMob> dungeonMobs = new ArrayList<>();
    private static final List<WeightedRandomChestContent> dungeonLoot = new ArrayList<>();

    /** Add or boost a mob's spawn weight in dungeon spawners. */
    public static int addDungeonMob(String mob, int rarity) {
        for (DungeonMob existing : dungeonMobs) {
            if (existing.type.equals(mob)) {
                existing.rarity += rarity;
                return existing.rarity;
            }
        }
        dungeonMobs.add(new DungeonMob(mob, rarity));
        return rarity;
    }

    /** Remove a mob from dungeon spawners. Returns its previous weight. */
    public static int removeDungeonMob(String mob) {
        Iterator<DungeonMob> iterator = dungeonMobs.iterator();
        while (iterator.hasNext()) {
            DungeonMob entry = iterator.next();
            if (entry.type.equals(mob)) {
                iterator.remove();
                return entry.rarity;
            }
        }
        return 0;
    }

    /** Pick a random mob name from the registry, defaulting to Skeleton. */
    public static String getRandomDungeonMob(Random rand) {
        if (dungeonMobs.isEmpty()) return "Skeleton";
        return dungeonMobs.get(rand.nextInt(dungeonMobs.size())).type;
    }

    public static void addDungeonLoot(ItemStack item, int weight) {
        addDungeonLoot(item, weight, 1, 1);
    }

    public static void addDungeonLoot(ItemStack item, int weight, int min, int max) {
        if (item != null) {
            dungeonLoot.add(new WeightedRandomChestContent(item, min, max, weight));
        }
    }

    /**
     * Remove every loot entry whose stack is item-equal to {@code item}
     * (matching ID and damage; ignoring stack size and NBT, like upstream).
     */
    public static void removeDungeonLoot(ItemStack item) {
        if (item == null) return;
        dungeonLoot.removeIf(entry -> entry.theItemId != null
                && entry.theItemId.itemID == item.itemID
                && entry.theItemId.getItemDamage() == item.getItemDamage());
    }

    /** Snapshot of the currently-registered dungeon loot. */
    public static List<WeightedRandomChestContent> getDungeonLoot() {
        return new ArrayList<>(dungeonLoot);
    }

    /** Single registered dungeon mob entry. */
    public static class DungeonMob {

        public final String type;
        public int rarity;

        public DungeonMob(String type, int rarity) {
            this.type = type;
            this.rarity = rarity;
        }
    }
}
