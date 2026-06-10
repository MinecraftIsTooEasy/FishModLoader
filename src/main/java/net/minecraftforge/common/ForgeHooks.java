package net.minecraftforge.common;

import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.WeightedRandom;
import net.minecraft.util.WeightedRandomItem;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Forge 1.6.4 ForgeHooks — fills in the methods that map cleanly to MITE
 * vanilla. Methods that need patched-vanilla state which doesn't exist on
 * stock 1.6.4 MITE (canHarvestBlock chain, blockStrength, getDigSpeed,
 * pick-block) remain conservative defaults — better to be slightly wrong
 * than to silently break tool behaviour.
 *
 * <p>Mod-tool registration (toolClasses, MinecraftForge#setBlockHarvestLevel)
 * is honoured by {@link #canToolHarvestBlock} and {@link #isToolEffective}
 * even though the vanilla harvest pipeline doesn't consult these maps yet.
 */
public class ForgeHooks {

    public static List<GrassEntry> grassList = new ArrayList<>();
    public static List<SeedEntry> seedList = new ArrayList<>();
    public static Map<Item, List<Object>> toolClasses = new HashMap<>();

    /**
     * Vanilla-only tool/harvest check: stock 1.6.4 MITE doesn't expose a
     * Forge-style {@code canHarvestBlock(Block)}; we conservatively allow
     * harvesting and let the vanilla strength check decide drops. Mod
     * tools that rely on Forge metadata go through
     * {@link #canToolHarvestBlock(Block, int, ItemStack)} instead.
     */
    public static boolean canHarvestBlock(Block block, EntityPlayer player, int metadata) {
        if (block == null) return false;
        return true;
    }

    /**
     * Honour mod-registered tool classes / harvest levels. If the mod has
     * declared a tool class for {@code stack.getItem()} via
     * {@link MinecraftForge#setToolClass}, and the block has a registered
     * harvest level for that class, the tool harvests when its level meets
     * the requirement. Otherwise return {@code true} so we don't break
     * vanilla pickaxe/axe/shovel behaviour.
     */
    public static boolean canToolHarvestBlock(Block block, int metadata, ItemStack stack) {
        if (block == null || stack == null) return false;
        List<Object> info = toolClasses.get(stack.getItem());
        if (info == null) return true;
        String toolClass = (String) info.get(0);
        int toolLevel = (Integer) info.get(1);
        int required = MinecraftForge.getBlockHarvestLevel(block, metadata, toolClass);
        if (required == -1) return true;
        return toolLevel >= required;
    }

    /**
     * Stock 1.6.4 MITE computes block strength inside
     * {@link EntityPlayer#getCurrentPlayerStrVsBlock} and exposes no
     * standalone hook. Returning a constant 1.0F mirrors what Forge
     * upstream does on a player-less call and is the safest default for
     * mods that read this value without further context.
     */
    public static float blockStrength(Block block, EntityPlayer player, World world, int x, int y, int z) {
        if (block == null) return 0.0F;
        if (player == null || world == null) return 1.0F;
        return player.getCurrentPlayerStrVsBlock(x, y, z, false);
    }

    /**
     * Ask MITE first via {@link Item#isEffectiveAgainstBlock(Block, int)};
     * fall back to mod-registered effectiveness if the item itself doesn't
     * declare it. This is the key path for tinkered tools / mod tools.
     */
    public static boolean isToolEffective(ItemStack stack, Block block, int metadata) {
        if (stack == null || block == null) return false;
        Item item = stack.getItem();
        if (item != null && item.isEffectiveAgainstBlock(block, metadata)) return true;
        List<Object> info = toolClasses.get(item);
        if (info == null) return false;
        String toolClass = (String) info.get(0);
        return MinecraftForge.isToolEffectiveAgainst(block, metadata, toolClass);
    }

    /**
     * Forge upstream populates default tool/block harvest tables here. On
     * MITE the vanilla harvest pipeline is materials-driven, so there is
     * no equivalent table to seed — leaving the maps empty lets vanilla
     * behaviour stand and only mod-registered tools get extra paths.
     */
    public static void initTools() {
        // intentionally empty — see method comment
    }

    /**
     * Sum {@link ItemArmor#damageReduceAmount}-equivalent across the four
     * armor slots. MITE replaces the legacy single damageReduceAmount
     * field with the {@link ItemArmor#getProtectionAfterDamageFactor}
     * method, which depends on the wearer; we approximate by querying it
     * with {@code player} as the entity. Mods that just want a coarse
     * "is the player armored" signal still get a meaningful number.
     */
    public static int getTotalArmorValue(EntityPlayer player) {
        if (player == null) return 0;
        int total = 0;
        ItemStack[] armor = player.inventory.armorInventory;
        for (int slot = 0; slot < armor.length; slot++) {
            ItemStack stack = armor[slot];
            if (stack == null) continue;
            Item item = stack.getItem();
            if (!(item instanceof ItemArmor)) continue;
            float factor = ((ItemArmor) item).getProtectionAfterDamageFactor(stack, player);
            // factor is "fraction of damage that gets through" (0..1).
            // Convert to a vanilla-style "damage reduce amount" in the
            // 0-20 range (1.0 = no protection -> 0; 0.0 = full -> 20).
            total += Math.round((1.0F - factor) * 5.0F);
        }
        return total;
    }

    /**
     * Pick a random plant block to drop from grass-bonemeal. Returns the
     * vanilla tall-grass block as a stable default until mods register
     * their own entries via {@link MinecraftForge#addGrassPlant}.
     */
    public static ItemStack getGrassSeed(World world) {
        if (seedList.isEmpty()) return null;
        SeedEntry entry = (SeedEntry) WeightedRandom.getRandomItem(world.rand, seedList);
        return entry == null || entry.seed == null ? null : entry.seed.copy();
    }

    /**
     * Forge upstream uses this to override the metadata of grass blocks
     * placed by bonemeal so plant mods can lay down custom variants.
     * Stock 1.6.4 MITE only has standard tall-grass metadata; returning 0
     * keeps vanilla bonemeal behaviour intact.
     */
    public static int getGrassMeta(World world, int x, int y, int z) {
        return 0;
    }

    public static void onLivingSetAttackTarget(EntityLiving target, Entity attacker) {
        if (!(attacker instanceof EntityLivingBase)) return;
        MinecraftForge.EVENT_BUS.post(new LivingSetAttackTargetEvent(target, (EntityLivingBase) attacker));
    }

    public static boolean onLivingUpdate(EntityLivingBase entity) {
        return MinecraftForge.EVENT_BUS.post(new LivingEvent.LivingUpdateEvent(entity));
    }

    public static boolean onLivingAttack(EntityLivingBase entity, DamageSource src, float amount) {
        return MinecraftForge.EVENT_BUS.post(new LivingAttackEvent(entity, src, amount));
    }

    public static float onLivingHurt(EntityLivingBase entity, DamageSource src, float amount) {
        LivingHurtEvent event = new LivingHurtEvent(entity, src, amount);
        if (MinecraftForge.EVENT_BUS.post(event)) return 0.0F;
        return event.ammount;
    }

    public static boolean onLivingDeath(EntityLivingBase entity, DamageSource src) {
        return MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(entity, src));
    }

    public static boolean onLivingDrops(EntityLivingBase entity, DamageSource src,
                                        ArrayList<EntityItem> drops, int looting,
                                        boolean recentlyHit, int special) {
        return MinecraftForge.EVENT_BUS.post(
                new LivingDropsEvent(entity, src, drops, looting, recentlyHit, special));
    }

    public static float onLivingFall(EntityLivingBase entity, float distance) {
        LivingFallEvent event = new LivingFallEvent(entity, distance);
        if (MinecraftForge.EVENT_BUS.post(event)) return 0.0F;
        return event.distance;
    }

    /**
     * Looting level for drop calculation. Mirrors upstream: prefer the
     * killer's looting if they're a living entity, else the responsible
     * entity on the {@code cause}, else 0. MITE's
     * {@link DamageSource#getResponsibleEntity} replaces upstream
     * {@code getEntity}.
     */
    public static int getLootingLevel(Entity target, Entity killer, DamageSource cause) {
        int level = 0;
        if (killer instanceof EntityLivingBase) {
            level = EnchantmentHelper.getLootingModifier((EntityLivingBase) killer);
        }
        if (cause != null) {
            Entity responsible = cause.getResponsibleEntity();
            if (responsible instanceof EntityLivingBase) {
                int causeLevel = EnchantmentHelper.getLootingModifier((EntityLivingBase) responsible);
                if (causeLevel > level) level = causeLevel;
            }
        }
        return level;
    }

    /**
     * Default pick-block: defer to vanilla {@link Block#idPicked}. Mods
     * that register custom pick-block behaviour patch the relevant block
     * subclass directly (or use {@link ForgeEventFactory} once that route
     * lands), but the public-API call still returns a sensible stack.
     */
    public static ItemStack onPickBlock(MovingObjectPosition target, EntityPlayer player, World world) {
        if (target == null || world == null) return null;
        if (!target.isBlock()) return null;
        int blockId = world.getBlockId(target.blockX, target.blockY, target.blockZ);
        if (blockId == 0) return null;
        Block block = Block.blocksList[blockId];
        if (block == null) return null;
        int picked = block.idPicked(world, target.blockX, target.blockY, target.blockZ);
        if (picked == 0) return null;
        int meta = world.getBlockMetadata(target.blockX, target.blockY, target.blockZ);
        return new ItemStack(picked, 1, meta);
    }

    public static class GrassEntry extends WeightedRandomItem {
        public final Block block;
        public final int metadata;
        public GrassEntry(Block block, int metadata, int weight) {
            super(weight);
            this.block = block;
            this.metadata = metadata;
        }
    }

    public static class SeedEntry extends WeightedRandomItem {
        public final ItemStack seed;
        public SeedEntry(ItemStack seed, int weight) {
            super(weight);
            this.seed = seed;
        }
    }
}
