package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Mixin(ChunkProviderServer.class)
public abstract class ChunkProviderServerMixin {

    @Shadow
    private World worldObj;

    @Shadow
    private Set<Long> chunksToUnload;

    @Shadow
    private Map<Long, Chunk> loadedChunkHashMap;

    @Shadow
    private List<Chunk> loadedChunks;

    @Shadow
    private net.minecraft.world.chunk.IChunkProvider currentChunkProvider;

    @Inject(method = "unloadChunksIfNotNearSpawn", at = @At("HEAD"), cancellable = true)
    private void fmlForgeUnloadChunks(int par1, int par2, CallbackInfo ci) {
        if (!(this.worldObj.provider.canRespawnHere() && DimensionManager.shouldLoadSpawn(this.worldObj.provider.dimensionId))) {
            ci.cancel();
        }
    }
}
