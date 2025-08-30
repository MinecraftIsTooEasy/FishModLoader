package net.xiaoyu233.fml.reload.transform.fix;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.AnvilChunkLoader;
import net.minecraft.Chunk;
import net.minecraft.ExtendedBlockStorage;
import net.xiaoyu233.fml.FishModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AnvilChunkLoader.class)
public class FixAntiCheat {
    @WrapWithCondition(method = {"readChunkFromNBT"}, at = {@At(value = "INVOKE", target = "Lnet/minecraft/AnvilChunkLoader;handleSectionChecksumFailure(Lnet/minecraft/ExtendedBlockStorage;)V")})
    private boolean fixBlocks(AnvilChunkLoader instance, ExtendedBlockStorage block_id, @Local Chunk chunk) {
        FishModLoader.LOGGER.warn("Chunk loading error: block sum unmatched at: " + chunk);
        return false;
    }

    @WrapWithCondition(method = {"readChunkFromNBT"}, at = {@At(value = "INVOKE", target = "Lnet/minecraft/AnvilChunkLoader;handleTileEntitiesChecksumFailure(Lnet/minecraft/Chunk;)V")})
    private boolean fixTileEntities(AnvilChunkLoader instance, Chunk entry) {
        FishModLoader.LOGGER.warn("Chunk loading error: tile entity sum unmatched at: " + entry);
        return false;
    }

    @WrapWithCondition(method = {"readChunkFromNBT"}, at = {@At(value = "INVOKE", target = "Lnet/minecraft/AnvilChunkLoader;handleEntitiesChecksumFailure(Lnet/minecraft/Chunk;)V")})
    private boolean fixEntities(AnvilChunkLoader instance, Chunk entities) {
        FishModLoader.LOGGER.warn("Chunk loading error: entity sum unmatched at: " + entities);
        return false;
    }
}
