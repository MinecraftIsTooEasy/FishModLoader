package net.xiaoyu233.fml.reload.transform.forge_compat;

import com.google.common.collect.ImmutableSetMultimap;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFace;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.PlaySoundAtEntityEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(World.class)
public class WorldMixin {
    @Shadow
    public WorldProvider provider;

    @Shadow
    public java.util.List loadedEntityList;

    @Shadow
    public java.util.List loadedTileEntityList;

    @Shadow
    public java.util.List addedTileEntityList;

    @Shadow
    private boolean scanningTileEntities;

    @Shadow
    public Chunk getChunkFromChunkCoords(int par1, int par2) { return null; }

    // ========================================================================
    // Forge-added fields
    // ========================================================================
    @Unique
    public static double MAX_ENTITY_RADIUS = 2.0D;

    @Unique
    public final MapStorage perWorldStorage = null;

    // ========================================================================
    // Event hook injections
    // ========================================================================

    @Inject(method = "playSoundAtEntity", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnPlaySoundAtEntity(Entity par1Entity, String par2Str, float par3, float par4, CallbackInfo ci) {
        PlaySoundAtEntityEvent event = new PlaySoundAtEntityEvent(par1Entity, par2Str, par3, par4);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            ci.cancel();
        }
    }

    @Inject(method = "playSoundToNearExcept", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnPlaySoundToNearExcept(EntityPlayer par1EntityPlayer, String par2Str, float par3, float par4, CallbackInfo ci) {
        PlaySoundAtEntityEvent event = new PlaySoundAtEntityEvent(par1EntityPlayer, par2Str, par3, par4);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            ci.cancel();
        }
    }

    // ========================================================================
    // Forge API methods
    // ========================================================================

    @Unique
    public void addTileEntity(net.minecraft.tileentity.TileEntity entity) {
        List dest = scanningTileEntities ? addedTileEntityList : loadedTileEntityList;
        dest.add(entity);
    }

    @Unique
    public boolean isBlockSolidOnSide(int x, int y, int z, ForgeDirection side) {
        return isBlockSolidOnSide(x, y, z, side, false);
    }

    @Unique
    public boolean isBlockSolidOnSide(int x, int y, int z, ForgeDirection side, boolean _default) {
        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000) {
            return _default;
        }
        Chunk chunk = this.getChunkFromChunkCoords(x >> 4, z >> 4);
        if (chunk == null || chunk.isEmpty()) {
            return _default;
        }
        World self = (World)(Object)this;
        int id = self.getBlockId(x, y, z);
        Block block = Block.blocksList[id];
        if (block == null) {
            return false;
        }
        int metadata = self.getBlockMetadata(x, y, z);
        if (block instanceof net.minecraft.block.BlockFarmland) {
            return side != ForgeDirection.DOWN && side != ForgeDirection.UP;
        }
        if (block instanceof net.minecraft.block.BlockStairs) {
            return (metadata & 4) != 0;
        }
        return block.isFaceFlatAndSolid(metadata, forgeDirectionToEnumFace(side.getOpposite()));
    }

    @Unique
    private static EnumFace forgeDirectionToEnumFace(ForgeDirection dir) {
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

    @Unique
    public ImmutableSetMultimap<ChunkCoordIntPair, ForgeChunkManager.Ticket> getPersistentChunks() {
        return ForgeChunkManager.getPersistentChunksFor((World)(Object)this);
    }

    @Unique
    public int getBlockLightOpacity(int x, int y, int z) {
        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000) {
            return 0;
        }
        if (y < 0 || y >= 256) {
            return 0;
        }
        return getChunkFromChunkCoords(x >> 4, z >> 4).getBlockLightOpacity(x & 15, y, z & 15);
    }

    @Unique
    public int countEntities(EnumCreatureType type, boolean forSpawnCount) {
        int count = 0;
        for (int x = 0; x < loadedEntityList.size(); x++) {
            Entity entity = (Entity) loadedEntityList.get(x);
            if (type.getCreatureClass().isAssignableFrom(entity.getClass())) {
                count++;
            }
        }
        return count;
    }
}
