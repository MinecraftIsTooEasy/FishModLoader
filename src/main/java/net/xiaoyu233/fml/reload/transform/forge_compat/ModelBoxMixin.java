package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.model.ModelBox;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Forge compatibility: {@code @SideOnly(Side.CLIENT)} removed from
 * class level. The annotation has been moved to the {@code render}
 * method only.
 */
@Mixin(ModelBox.class)
public abstract class ModelBoxMixin {
}
