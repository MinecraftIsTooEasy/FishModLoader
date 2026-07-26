package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.model.TexturedQuad;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Forge compatibility: {@code @SideOnly(Side.CLIENT)} removed from
 * class level.
 */
@Mixin(TexturedQuad.class)
public abstract class TexturedQuadMixin {
}
