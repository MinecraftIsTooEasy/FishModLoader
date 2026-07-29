package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.WorldEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

@Mixin(WorldServer.class)
public abstract class WorldServerMixin {

    @Shadow
    private MinecraftServer mcServer;

    @Shadow
    public net.minecraft.world.chunk.IChunkProvider theChunkProviderServer;

    @Shadow
    protected net.minecraft.world.storage.ISaveHandler saveHandler;

    @Shadow
    public net.minecraft.world.storage.MapStorage mapStorage;

    @Shadow
    protected net.minecraft.village.VillageCollection villageCollectionObj;

    @Shadow
    protected net.minecraft.village.VillageSiege villageSiegeObj;

    @Shadow
    public net.minecraft.world.Teleporter worldTeleporter;

    @Shadow
    public net.minecraft.world.WorldProvider provider;

    @Shadow
    public net.minecraft.world.chunk.IChunkProvider chunkProvider;

    @Shadow
    public java.util.Random rand;

    @Shadow
    public java.util.List loadedEntityList;

    @Shadow
    public java.util.List loadedTileEntityList;

    @Shadow
    public java.util.List playerEntities;

    @Shadow
    public java.util.Set activeChunkSet;

    @Shadow
    public int skylightSubtracted;

    @Shadow
    public net.minecraft.world.storage.WorldInfo worldInfo;

    @Shadow
    private int updateEntityTick;

    @Shadow
    public abstract ChunkProviderServer getChunkProvider();

    @Shadow
    public abstract void sendAndApplyBlockEvents();

    @Shadow
    public abstract long getTotalWorldTime();

    @Shadow
    public abstract int getHeight();

    @Unique
    protected Set<ChunkCoordIntPair> doneChunks = new HashSet<ChunkCoordIntPair>();

    @Unique
    public List<Teleporter> customTeleporters = new ArrayList<Teleporter>();

    @Unique
    public MapStorage perWorldStorage;

    @Redirect(method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/world/storage/ISaveHandler;Ljava/lang/String;ILnet/minecraft/world/WorldSettings;Lnet/minecraft/util/Profiler;Lnet/minecraft/util/ILogAgent;)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/storage/WorldInfo;setWorldTime(J)V"))
    private void fmlForgeSkipSetWorldTime(WorldInfo info, long time) {
        // No-op to prevent initial save data corruption
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void fmlForgeTickCustomTeleporters(CallbackInfo ci) {
        for (Teleporter tele : customTeleporters) {
            tele.removeStalePortalLocations(getTotalWorldTime());
        }
    }

    @Inject(method = "spawnRandomCreature", at = @At("HEAD"), cancellable = true)
    private void fmlForgeSpawnRandomCreature(EnumCreatureType par1EnumCreatureType,
                                              int par2, int par3, int par4, CallbackInfoReturnable<net.minecraft.world.biome.SpawnListEntry> cir) {
        List list = getChunkProvider().getPossibleCreatures(par1EnumCreatureType, par2, par3, par4);
        list = ForgeEventFactory.getPotentialSpawns((WorldServer)(Object)this, par1EnumCreatureType, par2, par3, par4, list);
        if (list != null && !list.isEmpty()) {
            cir.setReturnValue((net.minecraft.world.biome.SpawnListEntry)net.minecraft.util.WeightedRandom.getRandomItem(this.rand, list));
        } else {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "updateEntities", at = @At("HEAD"), cancellable = true)
    private void fmlForgeUpdateEntities(CallbackInfo ci) {
        if (playerEntities.isEmpty() && perWorldStorage == null) {
            if (updateEntityTick++ >= 1200) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "saveLevel", at = @At("TAIL"))
    private void fmlForgeSaveLevel(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Save((WorldServer)(Object)this));
    }

    @Inject(method = "saveAllData", at = @At("TAIL"))
    private void fmlForgeSaveAllData(CallbackInfo ci) {
        if (perWorldStorage != null) {
            perWorldStorage.saveAllData();
        }
    }

    @Unique
    public File getChunkSaveLocation() {
        return null;
    }
}
