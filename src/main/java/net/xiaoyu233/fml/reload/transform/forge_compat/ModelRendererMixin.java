package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.model.ModelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Forge compatibility: {@code @SideOnly(Side.CLIENT)} removed from
 * class level. The annotation has been moved to individual rendering
 * methods: {@code render}, {@code renderWithRotation},
 * {@code postRender}, and {@code compileDisplayList}.
 */
@Mixin(ModelRenderer.class)
public abstract class ModelRendererMixin {
}
