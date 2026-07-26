package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.gui.inventory.GuiContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link GuiContainer}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Disable GL_LIGHTING around {@code drawGuiContainerForegroundLayer}.</li>
 *   <li>Use item-specific {@link net.minecraft.client.gui.FontRenderer} for rendering.</li>
 *   <li>Add {@code drawHoveringText(List, int, int, FontRenderer)} overload.</li>
 * </ul>
 */
@Mixin(GuiContainer.class)
public class GuiContainerMixin {

    /**
     * Placeholder: The patch adds
     * {@code GL11.glDisable(GL11.GL_LIGHTING)} before and
     * {@code GL11.glEnable(GL11.GL_LIGHTING)} after the call to
     * {@code drawGuiContainerForegroundLayer}.
     * <p>
     * This is wrapping a method call inside the {@code drawScreen}
     * method body.
     */
    @Unique
    private void fmlForgeLightingAroundForeground() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for GL_LIGHTING wrapping.");
    }

    /**
     * Placeholder: The patch uses
     * {@code par1ItemStack.getItem().getFontRenderer(par1ItemStack)}
     * to get a custom FontRenderer for item rendering and hover text.
     * <p>
     * This replaces {@code this.fontRenderer} references in the
     * {@code drawItemStack} and tooltip methods.
     */
    @Unique
    private void fmlForgeCustomFontRenderer() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for getFontRenderer usage.");
    }

    /**
     * Placeholder: The patch extracts a new overloaded method
     * {@code drawHoveringText(List, int, int, FontRenderer)} from
     * the existing {@code func_102021_a} and makes
     * {@code func_102021_a} delegate to it.
     * <p>
     * This is a method extraction refactoring.
     */
    @Unique
    private void fmlForgeDrawHoveringText() {
        throw new UnsupportedOperationException(
                "Method extraction required. See patches for drawHoveringText.");
    }
}
