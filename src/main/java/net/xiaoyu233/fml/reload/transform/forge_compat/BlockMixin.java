package net.xiaoyu233.fml.reload.transform.forge_compat;

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
public class BlockMixin {
    @Shadow
    @Final
    public int blockID;

    @Shadow
    @Final
    public Material blockMaterial;

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
     * @reason Add Forge creature spawn check / delegate to block-specific logic
     */
    @Inject(method = "canCreatureSpawn", at = @At("HEAD"), cancellable = true)
    private void onCanCreatureSpawn(EnumCreatureType type, World world, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        Block self = (Block) (Object) this;
        int meta = world.getBlockMetadata(x, y, z);
        if (self instanceof net.minecraft.block.BlockStep) {
            cir.setReturnValue((meta & 8) == 8);
        } else if (self instanceof net.minecraft.block.BlockStairs) {
            cir.setReturnValue((meta & 4) != 0);
        } else {
            cir.setReturnValue(self.isFaceFlatAndSolid(meta, net.minecraft.util.EnumFace.TOP));
        }
    }

    /**
     * @reason Add fire spread hook - check whether the block has flammability or spread speed set
     */
    @Inject(method = "isBlockBurning", at = @At("HEAD"), cancellable = true)
    private void onIsBlockBurning(World world, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        Block self = (Block) (Object) this;
        cir.setReturnValue(blockFlammability[self.blockID] > 0 || blockFireSpreadSpeed[self.blockID] > 0);
    }

    /**
     * @reason Add Forge block placed event integration (OreDict)
     */
    @Inject(method = "onBlockAdded", at = @At("HEAD"), cancellable = true)
    private void onOnBlockAdded(World world, int x, int y, int z, CallbackInfo ci) {
        // Forge block placement hook stub - OreDictionary integration fires externally.
    }

    /**
     * @reason Add harvest check for player block removal
     */
    @Inject(method = "removeBlockByPlayer", at = @At("HEAD"), cancellable = true)
    private void onRemoveBlockByPlayer(World world, EntityPlayer player, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(world.setBlockToAir(x, y, z));
    }

    // ========== @Unique: New methods not present in MITE's Block (Forge additions) ==========

    /**
     * Forge harvestBlock - called when the player destroys a block with an item that can harvest it.
     */
    @Unique
    public void harvestBlock(World world, EntityPlayer player, int x, int y, int z, int metadata) {
        Block self = (Block) (Object) this;
        player.addStat(net.minecraft.stats.StatList.mineBlockStatArray[self.blockID], 1);

        if (self.canSilkHarvest(metadata) && EnchantmentHelper.getSilkTouchModifier(player)) {
            ArrayList<net.minecraft.item.ItemStack> items = new ArrayList<>();
            net.minecraft.item.ItemStack itemstack = self.createStackedBlock(metadata);
            if (itemstack != null) {
                items.add(itemstack);
            }
            ForgeEventFactory.fireBlockHarvesting(items, world, self, x, y, z, metadata, 0, 1.0f, true, player);
            for (net.minecraft.item.ItemStack is : items) {
                net.minecraft.block.BlockBreakInfo info = new net.minecraft.block.BlockBreakInfo(world, x, y, z).setHarvestedBy(player).setBlock(self, metadata);
                self.dropBlockAsEntityItem(info, is);
            }
        } else {
            int fortune = EnchantmentHelper.getFortuneModifier(player);
            self.dropBlockAsEntityItem(new net.minecraft.block.BlockBreakInfo(world, x, y, z).setHarvestedBy(player).setBlock(self, metadata));
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
        Block self = (Block) (Object) this;
        return self.getExplosionResistance(null);
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
        return this.blockMaterial.isSolid() && ((Block)(Object)this).renderAsNormalBlock();
    }

    @Unique
    public boolean isBlockSolidOnSide(World world, int x, int y, int z, ForgeDirection side) {
        Block self = (Block)(Object)this;
        int meta = world.getBlockMetadata(x, y, z);
        if (self instanceof net.minecraft.block.BlockFarmland) {
            return side != ForgeDirection.DOWN && side != ForgeDirection.UP;
        }
        if (self instanceof net.minecraft.block.BlockStairs) {
            return (meta & 4) != 0;
        }
        return self.isFaceFlatAndSolid(meta, toEnumFace(side.getOpposite()));
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
        if (side == ForgeDirection.UP && world.provider instanceof net.minecraft.world.WorldProviderEnd) {
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
        return ((Block)(Object)this) instanceof net.minecraft.block.ITileEntityProvider;
    }

    @Unique
    public net.minecraft.tileentity.TileEntity createTileEntity(World world, int metadata) {
        if (((Block)(Object)this) instanceof net.minecraft.block.ITileEntityProvider) {
            return ((net.minecraft.block.ITileEntityProvider)(Object)this).createNewTileEntity(world);
        }
        return null;
    }

    @Unique
    public boolean canSustainPlant(World world, int x, int y, int z, ForgeDirection direction, net.minecraftforge.common.IPlantable plant) {
        Block self = (Block)(Object)this;
        net.minecraftforge.common.EnumPlantType plantType = plant.getPlantType(world, x, y + direction.offsetY, z);
        switch (plantType) {
            case Desert:
                return self.blockID == Block.sand.blockID || self.blockID == Block.hardenedClay.blockID;
            case Nether:
                return self.blockID == Block.slowSand.blockID;
            case Crop:
                return self.blockID == Block.tilledField.blockID;
            case Cave:
                return ((BlockMixin)(Object)self).isBlockNormalCube(world, x, y, z);
            case Plains:
                return self.blockID == Block.grass.blockID || self.blockID == Block.dirt.blockID || self.blockID == Block.tilledField.blockID;
            case Water:
                return this.blockMaterial == Material.water && world.getBlockMetadata(x, y, z) == 0;
            case Beach:
                return self.blockID == Block.grass.blockID || self.blockID == Block.dirt.blockID || self.blockID == Block.sand.blockID || self.blockID == Block.gravel.blockID;
        }
        return false;
    }

    @Unique
    public boolean isFertile(World world, int x, int y, int z) {
        return ((Block)(Object)this).blockID == Block.tilledField.blockID && world.getBlockMetadata(x, y, z) > 0;
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
        return ((Block)(Object)this).canProvidePower() && side != -1;
    }

    @Unique
    public boolean isBlockFoliage(World world, int x, int y, int z) {
        return false;
    }

    @Unique
    public boolean canEntityDestroy(World world, int x, int y, int z, Entity entity) {
        if (entity instanceof net.minecraft.entity.boss.EntityWither) {
            return this.blockMaterial != Material.iron;
        }
        if (entity instanceof net.minecraft.entity.boss.EntityDragon) {
            return !this.blockMaterial.isSolid();
        }
        return true;
    }
}
