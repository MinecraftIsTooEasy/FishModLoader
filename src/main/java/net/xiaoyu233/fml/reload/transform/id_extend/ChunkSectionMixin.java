package net.xiaoyu233.fml.reload.transform.id_extend;

import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ExtendedBlockStorage.class)
public class ChunkSectionMixin {
    @Redirect(method = {
            "setExtBlockID",
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Debug;setErrorMessage(Ljava/lang/String;)V"))
    public void deletePrintBlockIdMessage(String message) {
    }
}
