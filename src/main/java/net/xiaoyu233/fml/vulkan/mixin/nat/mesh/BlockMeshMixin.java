package net.xiaoyu233.fml.vulkan.mixin.nat.mesh;

import net.minecraft.Block;
import net.xiaoyu233.fml.vulkan.render.mesh.ThreadSlots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Native area "mesh": the six bound arrays of {@code Block} get {@link ThreadSlots#slotCount()} entries (2.3 #2). */
@Mixin(Block.class)
public abstract class BlockMeshMixin {
    @ModifyConstant(method = "<init>(ILnet/minecraft/Material;Lnet/minecraft/BlockConstants;)V", constant = @Constant(intValue = 2), require = 6)
    private int fml$slotCount(int original) {
        return ThreadSlots.slotCount();
    }
}
