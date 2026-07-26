package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.entity.RenderBiped;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link RenderBiped}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>New {@code getArmorResource} method.</li>
 *   <li>Use {@code ForgeHooksClient.getArmorModel}.</li>
 *   <li>Color handling extended beyond CLOTH armor.</li>
 *   <li>Custom item renderer support.</li>
 *   <li>Dynamic render passes.</li>
 * </ul>
 */
@Mixin(RenderBiped.class)
public class RenderBipedMixin {

    /**
     * Placeholder: The patch adds a new public static method
     * {@code getArmorResource} and modifies the armor-rendering
     * methods to use it instead of the deprecated methods.
     * <p>
     * It also adds {@code ForgeHooksClient.getArmorModel} calls,
     * extends color handling beyond CLOTH material, and adds custom
     * item renderer + dynamic render pass support.
     * <p>
     * These are extensive body modifications requiring direct patching.
     */
    @Unique
    private void fmlForgeArmorRendering() {
        throw new UnsupportedOperationException(
                "Extensive body changes required. See patches for RenderBiped armor rendering.");
    }
}
