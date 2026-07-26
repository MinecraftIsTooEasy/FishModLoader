package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.entity.EntityOtherPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link EntityOtherPlayerMP}.
 * <p>
 * The Forge patch renames {@code getEyeHeight()} to
 * {@code getDefaultEyeHeight()} with {@code @Override}.
 * This method needs to exist on the class (inherited or directly).
 * <p>
 * If the superclass already declares {@code getDefaultEyeHeight()},
 * this is a no-op. Otherwise, this mixin provides a {@code @Unique}
 * method that matches the renamed signature.
 * <p>
 * <b>Note:</b> If the superclass does <i>not</i> define
 * {@code getDefaultEyeHeight()}, the patch must rename the method
 * declaration in the bytecode. This mixin documents the intent.
 */
@Mixin(EntityOtherPlayerMP.class)
public class EntityOtherPlayerMPMixin {

    /**
     * Placeholder: The Forge patch renames the original
     * {@code getEyeHeight()} override to {@code getDefaultEyeHeight()}.
     * This requires a direct method-rename in the bytecode of the
     * target class. The mixin framework cannot rename existing methods.
     */
    @Unique
    public float getDefaultEyeHeight() {
        return 1.82F;
    }
}
