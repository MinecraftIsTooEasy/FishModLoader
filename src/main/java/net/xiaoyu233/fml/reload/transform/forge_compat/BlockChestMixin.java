package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockChest;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockChest.class)
public abstract class BlockChestMixin {
    @Shadow
    public int blockID;

    @Inject(method = "canPlaceBlockAt", at = @At("HEAD"), cancellable = true)
    private void onCanPlaceBlockAt(World world, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        int count = 0;
        if (world.getBlockId(x - 1, y, z) == this.blockID) ++count;
        if (world.getBlockId(x + 1, y, z) == this.blockID) ++count;
        if (world.getBlockId(x, y, z - 1) == this.blockID) ++count;
        if (world.getBlockId(x, y, z + 1) == this.blockID) ++count;
        cir.setReturnValue(count <= 1);
    }
}
