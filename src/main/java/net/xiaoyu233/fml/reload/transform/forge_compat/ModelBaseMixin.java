package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.model.ModelBase;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Forge compatibility: {@code @SideOnly(Side.CLIENT)} removed from
 * class level. The annotation has been moved to the {@code render}
 * method only.
 * <p>
 * Patch: removes the class-level @SideOnly, adds @SideOnly to render().
 */
@Mixin(ModelBase.class)
public abstract class ModelBaseMixin {
}
