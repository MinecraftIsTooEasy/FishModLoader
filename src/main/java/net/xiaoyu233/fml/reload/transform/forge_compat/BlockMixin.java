package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.EnumPlantType;
import net.minecraft.world.Explosion;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.stats.StatList;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.BlockStep;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockFarmland;
import net.minecraft.block.BlockBreakInfo;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFace;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldProviderEnd;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

@Mixin(Block.class)
public abstract class BlockMixin {
    @Shadow
    @Final
    public int blockID;

    @Shadow
    @Final
    public Material blockMaterial;

    @Shadow
    public abstract boolean isFaceFlatAndSolid(int metadata, EnumFace face);

    @Shadow
    public abstract boolean renderAsNormalBlock();

    @Shadow
    public abstract boolean canProvidePower();

    @Shadow
    public abstract boolean canSilkHarvest(int metadata);

    @Shadow
    public abstract ItemStack createStackedBlock(int metadata);

    @Shadow
    public abstract int dropBlockAsEntityItem(BlockBreakInfo info);

    @Shadow
    public abstract int dropBlockAsEntityItem(BlockBreakInfo info, ItemStack stack);

    @Shadow
    public abstract float getExplosionResistance(Explosion explosion);

    // Mixin forbids non-private static fields in mixin classes. Forge's public
    // Block.blockFireSpreadSpeed/blockFlammability field API cannot be exposed
    // safely here; retain the functional setBurnProperties/method API instead.
    @Unique
    private static int[] blockFireSpreadSpeed = new int[4096];

    @Unique
    private static int[] blockFlammability = new int[4096];

    @Unique
    private ThreadLocal<Object> tileEntityCache = new ThreadLocal<>();

    // ========== @Inject: Methods that exist in (patched) Block ==========

    /**
     * Forge addition: allows blocks to control creature spawning.
     * MITE does not have this method; made @Unique so Forge mods can call it.
     * SpawnerAnimals queries this via canCreatureTypeSpawnOn.
     */
    @Unique
    public boolean canCreatureSpawn(EnumCreatureType type, World world, int x, int y, int z) {
        int meta = world.getBlockMetadata(x, y, z);
        if ((Object) this instanceof BlockStep) {
            return (meta & 8) == 8;
        } else if ((Object) this instanceof BlockStairs) {
            return (meta & 4) != 0;
        } else {
            return this.isFaceFlatAndSolid(meta, EnumFace.TOP);
        }
    }

    /**
     * Forge addition: whether this block is actively burning.
     * MITE does not have this method; made @Unique so Forge mods can call it.
     * BlockFire should query this when deciding fire spread.
     */
    @Unique
    public boolean isBlockBurning(World world, int x, int y, int z) {
        return blockFlammability[this.blockID] > 0 || blockFireSpreadSpeed[this.blockID] > 0;
    }

    /**
     * Forge addition: called when a player removes a block.
     * MITE uses onUnderminedByPlayer instead; this shim allows Forge mods
     * to call the vanilla-named method. Returns true on success.
     */
    @Unique
    public boolean removeBlockByPlayer(World world, EntityPlayer player, int x, int y, int z) {
        return world.setBlockToAir(x, y, z);
    }

    /**
     * Hook into MITE's onUnderminedByPlayer to fire the Forge removeBlockByPlayer
     * hook. This is the MITE equivalent of vanilla's removeBlockByPlayer call point.
     */
    @Inject(method = "onUnderminedByPlayer(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;III)V",
            at = @At("HEAD"))
    private void fmlForgeOnUnderminedByPlayer(
            World world, EntityPlayer player, int x, int y, int z, CallbackInfo ci) {
        // Fires the Forge hook; currently a no-op shim — mods can override
        // removeBlockByPlayer on their own Block subclasses.
    }

    // ========== @Unique: New methods not present in MITE's Block (Forge additions) ==========

    /**
     * Forge harvestBlock - called when the player destroys a block with an item that can harvest it.
     */
    @Unique
    public void harvestBlock(World world, EntityPlayer player, int x, int y, int z, int metadata) {
        player.addStat(StatList.mineBlockStatArray[this.blockID], 1);

        if (this.canSilkHarvest(metadata) && EnchantmentHelper.getSilkTouchModifier(player)) {
            ArrayList<ItemStack> items = new ArrayList<>();
            ItemStack itemstack = this.createStackedBlock(metadata);
            if (itemstack != null) {
                items.add(itemstack);
            }
            ForgeEventFactory.fireBlockHarvesting(items, world, (Block) (Object) this, x, y, z, metadata, 0, 1.0f, true, player);
            for (ItemStack is : items) {
                BlockBreakInfo info = new BlockBreakInfo(world, x, y, z).setHarvestedBy(player).setBlock((Block) (Object) this, metadata);
                this.dropBlockAsEntityItem(info, is);
            }
        } else {
            int fortune = EnchantmentHelper.getFortuneModifier(player);
            this.dropBlockAsEntityItem(new BlockBreakInfo(world, x, y, z).setHarvestedBy(player).setBlock((Block) (Object) this, metadata));
        }
    }

    /**
     * Forge getPlayerRelativeBlockHardness - returns the player's relative block breaking speed via ForgeHooks.
     */
    @Unique
    public float getPlayerRelativeBlockHardness(EntityPlayer player, World world, int x, int y, int z) {
        return ForgeHooks.blockStrength((Block) (Object) this, player, world, x, y, z);
    }

    /**
     * Forge getExplosionResistance - location-aware version.
     */
    @Unique
    public float getExplosionResistance(Entity par1Entity, World world, int x, int y, int z, double explosionX, double explosionY, double explosionZ) {
        return this.getExplosionResistance((Explosion) null);
    }

    /**
     * Get the filled percentage for fluid blocks.
     */
    @Unique
    public float getFilledPercentage(World world, int x, int y, int z) {
        return -1.0f;
    }

    /**
     * Get the direction of the bed block.
     */
    @Unique
    public int getBedDirection(IBlockAccess world, int x, int y, int z) {
        return 0;
    }

    /**
     * Check if this is the foot of a bed.
     */
    @Unique
    public boolean isBedFoot(IBlockAccess world, int x, int y, int z) {
        return false;
    }

    /**
     * Check if this block is a bed.
     */
    @Unique
    public boolean isBed(World world, int x, int y, int z, EntityLivingBase player) {
        return false;
    }

    /**
     * Check if this block can be replaced by growing leaves.
     */
    @Unique
    public boolean canBeReplacedByLeaves(World world, int x, int y, int z) {
        return false;
    }

    /**
     * Get the enchantment power bonus this block provides.
     */
    @Unique
    public float getEnchantPower(World world, int x, int y, int z) {
        return 0.0f;
    }

    /**
     * Check if this block can be used as a beacon base.
     */
    @Unique
    public boolean isBeaconBase(World worldObj, int x, int y, int z, int beaconX, int beaconY, int beaconZ) {
        return false;
    }

    /**
     * Rotate the block around the specified axis.
     */
    @Unique
    public boolean rotateBlock(World worldObj, int x, int y, int z, ForgeDirection axis) {
        return false;
    }

    // ========== ForgeDirection → EnumFace mapping ==========
    @Unique
    private static EnumFace toEnumFace(ForgeDirection dir) {
        switch (dir) {
            case DOWN: return EnumFace.BOTTOM;
            case UP: return EnumFace.TOP;
            case NORTH: return EnumFace.NORTH;
            case SOUTH: return EnumFace.SOUTH;
            case WEST: return EnumFace.WEST;
            case EAST: return EnumFace.EAST;
            default: return EnumFace.TOP;
        }
    }

    // ========== Additional Forge API methods ==========

    @Unique
    public int getLightValue(IBlockAccess world, int x, int y, int z) {
        return Block.lightValue[this.blockID];
    }

    @Unique
    public boolean isLadder(World world, int x, int y, int z, EntityLivingBase entity) {
        return false;
    }

    @Unique
    public boolean isBlockNormalCube(World world, int x, int y, int z) {
        return this.blockMaterial.isSolid() && this.renderAsNormalBlock();
    }

    @Unique
    public boolean isBlockSolidOnSide(World world, int x, int y, int z, ForgeDirection side) {
        int meta = world.getBlockMetadata(x, y, z);
        if ((Object) this instanceof BlockFarmland) {
            return side != ForgeDirection.DOWN && side != ForgeDirection.UP;
        }
        if ((Object) this instanceof BlockStairs) {
            return (meta & 4) != 0;
        }
        return this.isFaceFlatAndSolid(meta, toEnumFace(side.getOpposite()));
    }

    @Unique
    public boolean isAirBlock(World world, int x, int y, int z) {
        return false;
    }

    @Unique
    public boolean canHarvestBlock(EntityPlayer player, int meta) {
        return ForgeHooks.canHarvestBlock((Block)(Object)this, player, meta);
    }

    @Unique
    public int getFlammability(IBlockAccess world, int x, int y, int z, int metadata, ForgeDirection face) {
        return BlockMixin.blockFlammability[this.blockID];
    }

    @Unique
    public boolean isFlammable(IBlockAccess world, int x, int y, int z, int metadata, ForgeDirection face) {
        return getFlammability(world, x, y, z, metadata, face) > 0;
    }

    @Unique
    public int getFireSpreadSpeed(World world, int x, int y, int z, int metadata, ForgeDirection face) {
        return BlockMixin.blockFireSpreadSpeed[this.blockID];
    }

    @Unique
    public boolean isFireSource(World world, int x, int y, int z, int metadata, ForgeDirection side) {
        if (side == ForgeDirection.UP && world.provider instanceof WorldProviderEnd) {
            return true;
        }
        return false;
    }

    @Unique
    private static void setBurnProperties(int id, int encouragement, int flammability) {
        blockFireSpreadSpeed[id] = encouragement;
        blockFlammability[id] = flammability;
    }

    @Unique
    public boolean hasTileEntity(int metadata) {
        return ((Block)(Object)this) instanceof ITileEntityProvider;
    }

    @Unique
    public TileEntity createTileEntity(World world, int metadata) {
        if (((Block)(Object)this) instanceof ITileEntityProvider) {
            return ((ITileEntityProvider)(Object)this).createNewTileEntity(world);
        }
        return null;
    }

    @Unique
    public boolean canSustainPlant(World world, int x, int y, int z, ForgeDirection direction, IPlantable plant) {
        EnumPlantType plantType = plant.getPlantType(world, x, y + direction.offsetY, z);
        switch (plantType) {
            case Desert:
                return this.blockID == Block.sand.blockID || this.blockID == Block.hardenedClay.blockID;
            case Nether:
                return this.blockID == Block.slowSand.blockID;
            case Crop:
                return this.blockID == Block.tilledField.blockID;
            case Cave:
                return this.isBlockNormalCube(world, x, y, z);
            case Plains:
                return this.blockID == Block.grass.blockID || this.blockID == Block.dirt.blockID || this.blockID == Block.tilledField.blockID;
            case Water:
                return this.blockMaterial == Material.water && world.getBlockMetadata(x, y, z) == 0;
            case Beach:
                return this.blockID == Block.grass.blockID || this.blockID == Block.dirt.blockID || this.blockID == Block.sand.blockID || this.blockID == Block.gravel.blockID;
        }
        return false;
    }

    @Unique
    public boolean isFertile(World world, int x, int y, int z) {
        return this.blockID == Block.tilledField.blockID && world.getBlockMetadata(x, y, z) > 0;
    }

    @Unique
    public ItemStack getPickBlock(MovingObjectPosition target, World world, int x, int y, int z) {
        int id = world.getBlockId(x, y, z);
        if (id == 0) return null;
        return new ItemStack(id, 1, world.getBlockMetadata(x, y, z));
    }

    @Unique
    public boolean isWood(World world, int x, int y, int z) {
        return false;
    }

    @Unique
    public boolean isLeaves(World world, int x, int y, int z) {
        return false;
    }

    @Unique
    public boolean canSustainLeaves(World world, int x, int y, int z) {
        return false;
    }

    @Unique
    public float getEnchantPowerBonus(World world, int x, int y, int z) {
        return 0.0f;
    }

    @Unique
    public boolean canConnectRedstone(IBlockAccess world, int x, int y, int z, int side) {
        return this.canProvidePower() && side != -1;
    }

    @Unique
    public boolean isBlockFoliage(World world, int x, int y, int z) {
        return false;
    }

    @Unique
    public boolean canEntityDestroy(World world, int x, int y, int z, Entity entity) {
        if (entity instanceof EntityWither) {
            return this.blockMaterial != Material.iron;
        }
        if (entity instanceof EntityDragon) {
            return !this.blockMaterial.isSolid();
        }
        return true;
    }
}
