package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link net.minecraft.client.renderer.EntityRenderer}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Riding entity check also calls {@code canRiderInteract()}.</li>
 *   <li>{@code updateFovModifierHand} handles non-EntityPlayerSP.</li>
 *   <li>{@code orientCamera} delegates to
 *       {@code ForgeHooksClient.orientBedCamera}.</li>
 *   <li>Render pass hooks via {@code ForgeHooksClient.setRenderPass}.</li>
 *   <li>{@code drawBlockHighlight} via
 *       {@code ForgeHooksClient.onDrawBlockHighlight}.</li>
 *   <li>Particle rendering moved after clouds.</li>
 *   <li>{@code RenderWorldLastEvent} fired.</li>
 * </ul>
 */
@Mixin(net.minecraft.client.renderer.EntityRenderer.class)
public class EntityRendererMixin {

    @Shadow
    private Minecraft mc;

    /**
     * Placeholder: The patch adds {@code && !entity.canRiderInteract()}
     * to a condition inside the entity-rendering loop.
     * <p>
     * This is a single expression change requiring direct patching.
     */
    @Unique
    private void fmlForgeCanRiderInteract() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for canRiderInteract check.");
    }

    /**
     * Placeholder: The patch changes
     * {@code updateFovModifierHand} to handle the case where
     * {@code renderViewEntity} is not an {@link net.minecraft.client.entity.EntityPlayerSP}.
     */
    @Unique
    private void fmlForgeUpdateFovModifierHand() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for non-EntityPlayerSP FOV.");
    }

    /**
     * Placeholder: The patch changes {@code orientCamera} to use
     * {@code ForgeHooksClient.orientBedCamera} instead of inline
     * bed-rotation logic.
     */
    @Unique
    private void fmlForgeOrientBedCamera() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for orientBedCamera.");
    }

    /**
     * Placeholder: The patch adds render pass hooks
     * ({@code ForgeHooksClient.setRenderPass}),
     * {@code ForgeHooksClient.onDrawBlockHighlight},
     * particle rendering relocation, and
     * {@code ForgeHooksClient.dispatchRenderLast}.
     * <p>
     * These are deeply nested body changes inside
     * {@code renderWorld}.
     */
    @Unique
    private void fmlForgeRenderWorldChanges() {
        throw new UnsupportedOperationException(
                "Extensive body changes required. See patches for renderWorld hooks.");
    }
}
