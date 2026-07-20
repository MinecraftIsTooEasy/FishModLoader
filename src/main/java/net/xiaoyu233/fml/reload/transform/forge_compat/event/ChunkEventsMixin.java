package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import net.minecraft.entity.Entity;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.world.ChunkEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Chunk.class)
public abstract class ChunkEventsMixin {
    @Shadow @Final public int xPosition;
    @Shadow @Final public int zPosition;

    @Inject(method = "addEntity(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"))
    private void fmlForgeOnAddEntity(Entity entity, CallbackInfo callbackInfo) {
        int oldChunkX = (int) Math.floor(entity.posX / 16.0D);
        int oldChunkZ = (int) Math.floor(entity.posZ / 16.0D);
        MinecraftForge.EVENT_BUS.post(new EntityEvent.EnteringChunk(entity, this.xPosition, this.zPosition, oldChunkX, oldChunkZ));
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
