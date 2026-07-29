package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.IChunkProvider;
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

    // Only shadow what this mixin actually uses. Every unused @Shadow is a
    // hard mixin-apply failure waiting to happen: MITE renames or removes
    // members freely, and a single unresolvable @Shadow aborts the whole
    // mixin, silently disabling every Forge hook in this file.
    @Unique
    protected Set<ChunkCoordIntPair> doneChunks = new HashSet<ChunkCoordIntPair>();

    @Unique
    public List<Teleporter> customTeleporters = new ArrayList<Teleporter>();

    @Unique
    public MapStorage perWorldStorage;

    // World.updateEntityTick is private, so a subclass mixin cannot @Shadow it.
    // This mixin only needs its own idle counter, so keep one locally.
    @Unique
    private int fmlForgeIdleTicks;

    @Redirect(method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/world/storage/ISaveHandler;Ljava/lang/String;ILnet/minecraft/world/WorldSettings;Lnet/minecraft/util/Profiler;Lnet/minecraft/util/ILogAgent;)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/storage/WorldInfo;setWorldTime(J)V"))
    private void fmlForgeSkipSetWorldTime(WorldInfo info, long time) {
        // No-op to prevent initial save data corruption
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void fmlForgeTickCustomTeleporters(CallbackInfo ci) {
        for (Teleporter tele : customTeleporters) {
            tele.removeStalePortalLocations(((WorldServer)(Object)this).getTotalWorldTime());
        }
    }

    // MITE has no spawnRandomCreature method; the Forge hook cannot be wired
    // at this target without replacing MITE's different spawn pipeline.

    @Inject(method = "updateEntities", at = @At("HEAD"), cancellable = true)
    private void fmlForgeUpdateEntities(CallbackInfo ci) {
        // Mixin @Shadow does not search superclasses for FIELDS (only methods),
        // and playerEntities is declared on World, not WorldServer. Read it
        // through the target type instead of shadowing it.
        if (((WorldServer)(Object)this).playerEntities.isEmpty() && perWorldStorage == null) {
            if (fmlForgeIdleTicks++ >= 1200) {
                ci.cancel();
            }
        } else {
            // Without this reset the counter stays saturated, so the second
            // and later idle periods would cancel updateEntities immediately
            // instead of after the intended 1200-tick grace period.
            fmlForgeIdleTicks = 0;
        }
    }

    @Inject(method = "saveLevel", at = @At("TAIL"))
    private void fmlForgeSaveLevel(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Save((WorldServer)(Object)this));
    }

    @Inject(method = "saveChunkData()V", at = @At("TAIL"))
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
