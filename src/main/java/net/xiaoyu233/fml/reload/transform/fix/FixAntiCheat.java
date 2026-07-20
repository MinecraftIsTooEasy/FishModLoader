package net.xiaoyu233.fml.reload.transform.fix;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.xiaoyu233.fml.FishModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AnvilChunkLoader.class)
public class FixAntiCheat {
    @WrapWithCondition(method = {"readChunkFromNBT"}, at = {@At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/storage/AnvilChunkLoader;handleSectionChecksumFailure(Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;)V")})
    private boolean fixBlocks(AnvilChunkLoader instance, ExtendedBlockStorage block_id) {
        FishModLoader.LOGGER.warn("Chunk loading error: block sum unmatched");
        return false;
    }

    @WrapWithCondition(method = {"readChunkFromNBT"}, at = {@At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/storage/AnvilChunkLoader;handleTileEntitiesChecksumFailure(Lnet/minecraft/world/chunk/Chunk;)V")})
    private boolean fixTileEntities(AnvilChunkLoader instance, Chunk entry) {
        FishModLoader.LOGGER.warn("Chunk loading error: tile entity sum unmatched");
        return false;
    }

    @WrapWithCondition(method = {"readChunkFromNBT"}, at = {@At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/storage/AnvilChunkLoader;handleEntitiesChecksumFailure(Lnet/minecraft/world/chunk/Chunk;)V")})
    private boolean fixEntities(AnvilChunkLoader instance, Chunk entities) {
        FishModLoader.LOGGER.warn("Chunk loading error: entity sum unmatched");
        return false;
    }
}
