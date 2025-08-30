package net.xiaoyu233.fml.reload.transform.fix;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.Block;
import net.minecraft.EntityFallingSand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityFallingSand.class)
public class FixFallingSand {
    @WrapOperation(method = "canDislodgeOrCrushBlockAt", at = @At(value = "INVOKE", target = "Lnet/minecraft/Block;isDislodgedOrCrushedByFallingBlock(ILnet/minecraft/Block;I)Z"))
    private static boolean fixNPE(Block instance, int metadata, Block falling_block, int falling_block_metadata, Operation<Boolean> original) {
        if (instance == null) {
            return true;
        }
        return original.call(instance, metadata, falling_block, falling_block_metadata);
    }
}
