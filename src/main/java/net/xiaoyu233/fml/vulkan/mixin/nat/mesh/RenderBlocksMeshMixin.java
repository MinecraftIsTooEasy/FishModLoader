package net.xiaoyu233.fml.vulkan.mixin.nat.mesh;

import net.minecraft.IBlockAccess;
import net.minecraft.RenderBlocks;
import net.minecraft.World;
import net.xiaoyu233.fml.vulkan.render.mesh.SectionSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mesh": flower pot metadata override goes to the snapshot, never to the live world (2.3 #12). */
@Mixin(RenderBlocks.class)
public abstract class RenderBlocksMeshMixin {
    @Shadow
    public IBlockAccess blockAccess;

    @Redirect(method = "renderBlockFlowerpot(Lnet/minecraft/BlockFlowerPot;III)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/World;setBlockMetadataWithNotify(IIIII)Z"), require = 2)
    private boolean fml$flowerpotMetadata(World world, int x, int y, int z, int metadata, int flags) {
        if (this.blockAccess instanceof SectionSnapshot snapshot) {
            snapshot.overrideMetadata(x, y, z, metadata);
            return true;
        }
        return world.setBlockMetadataWithNotify(x, y, z, metadata, flags);
    }
}
