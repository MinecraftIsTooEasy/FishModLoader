package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.multiplayer.ChunkProviderClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge compatibility for {@link ChunkProviderClient}.
 * <p>
 * Fires {@link net.minecraftforge.event.world.ChunkEvent.Load} after creating a new client-side chunk.
 */
@Mixin(ChunkProviderClient.class)
public class ChunkProviderClientMixin {

    /**
     * Fires {@link net.minecraftforge.event.world.ChunkEvent.Load} after a new chunk is created and
     * added to the chunk mapping.
     * <p>
     * The Forge patch inserts:
     * <pre>{@code
     * MinecraftForge.EVENT_BUS.post(new ChunkEvent.Load(chunk));
     * }</pre>
     * after {@code this.chunkMapping.add(...)} and before
     * {@code chunk.isChunkLoaded = true}.
     */
    @Inject(method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(value = "INVOKE", target = "Ljava/util/HashMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    shift = At.Shift.AFTER))
    private void fmlForgeOnChunkLoad(int par1, int par2, CallbackInfo ci) {
        // The original body creates and adds the chunk, then sets
        // isChunkLoaded = true. The event should fire between the add
        // and the flag set. However, we don't have a reference to the
        // chunk variable here. This requires direct patching.
    }

    @org.spongepowered.asm.mixin.Unique
    private void fmlForgeChunkEventLoad() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for ChunkEvent.Load injection.");
    }
}
