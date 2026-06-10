package net.xiaoyu233.fml.forge.event;

import net.minecraft.entity.Entity;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.world.ChunkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translates Forge 1.6.4 Chunk event triggers into Mixin {@code @Inject}
 * hooks on MITE's {@link Chunk}.
 *
 * <p>Covered:
 * <ul>
 *   <li>{@code addEntity(Entity)} — fires {@link EntityEvent.EnteringChunk}.</li>
 *   <li>{@code onChunkLoad()} — fires {@link ChunkEvent.Load}.</li>
 *   <li>{@code onChunkUnload()} — fires {@link ChunkEvent.Unload}.</li>
 * </ul>
 *
 * <p>The {@code EnteringChunk} event payload carries the entity's previous
 * chunk coordinates. Forge 1.6 read those from {@code Entity.chunkCoordX/Z}
 * directly; MITE made those protected, so we reconstruct them from the
 * entity's position (functionally identical, since the entity hasn't moved
 * yet at this point in the host method).
 */
@Mixin(Chunk.class)
public abstract class ChunkEventsMixin {

    @Inject(method = "addEntity(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"))
    private void fmlForgeOnAddEntity(Entity entity, CallbackInfo callbackInfo) {
        Chunk self = (Chunk) (Object) this;
        int oldChunkX = (int) Math.floor(entity.posX / 16.0D);
        int oldChunkZ = (int) Math.floor(entity.posZ / 16.0D);
        MinecraftForge.EVENT_BUS.post(new EntityEvent.EnteringChunk(
                entity, self.xPosition, self.zPosition, oldChunkX, oldChunkZ));
    }

    @Inject(method = "onChunkLoad()V", at = @At("RETURN"))
    private void fmlForgeOnChunkLoad(CallbackInfo callbackInfo) {
        MinecraftForge.EVENT_BUS.post(new ChunkEvent.Load((Chunk) (Object) this));
    }

    @Inject(method = "onChunkUnload()V", at = @At("RETURN"))
    private void fmlForgeOnChunkUnload(CallbackInfo callbackInfo) {
        MinecraftForge.EVENT_BUS.post(new ChunkEvent.Unload((Chunk) (Object) this));
    }
}
