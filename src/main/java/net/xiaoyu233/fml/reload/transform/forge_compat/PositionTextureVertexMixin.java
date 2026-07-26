package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.model.PositionTextureVertex;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Forge compatibility: {@code @SideOnly(Side.CLIENT)} removed from
 * class level.
 */
@Mixin(PositionTextureVertex.class)
public abstract class PositionTextureVertexMixin {
}
