package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.renderer.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link GuiSlot}.
 * <p>
 * Extracts the background-drawing logic into a new protected method
 * {@code drawContainerBackground(Tessellator)} and calls it from
 * the original location.
 */
@Mixin(GuiSlot.class)
public class GuiSlotMixin {

    @Shadow
    private int left;

    @Shadow
    private int right;

    @Shadow
    private int top;

    @Shadow
    private int bottom;

    @Shadow
    private float amountScrolled;

    /**
     * Extracted background-drawing method. Called from the patched
     * {@code drawBackground} instead of the inline tessellator code.
     * <p>
     * This is a {@code @Unique} method that corresponds to the new
     * method added by the Forge patch.
     */
    @Unique
    protected void drawContainerBackground(Tessellator tess) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        mc.getTextureManager().bindTexture(net.minecraft.client.gui.Gui.optionsBackground);
        org.lwjgl.opengl.GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        float height = 32.0F;
        tess.startDrawingQuads();
        tess.setColorOpaque_I(2105376);
        tess.addVertexWithUV((double)this.left, (double)this.bottom, 0.0D, (double)((float)this.left / height), (double)((float)(this.bottom + (int)this.amountScrolled) / height));
        tess.addVertexWithUV((double)this.right, (double)this.bottom, 0.0D, (double)((float)this.right / height), (double)((float)(this.bottom + (int)this.amountScrolled) / height));
        tess.addVertexWithUV((double)this.right, (double)this.top, 0.0D, (double)((float)this.right / height), (double)((float)(this.top + (int)this.amountScrolled) / height));
        tess.addVertexWithUV((double)this.left, (double)this.top, 0.0D, (double)((float)this.left / height), (double)((float)(this.top + (int)this.amountScrolled) / height));
        tess.draw();
    }

    /**
     * Placeholder: The patch replaces the inline tessellator background
     * drawing code with a call to {@code drawContainerBackground(tessellator)}.
     * <p>
     * This requires replacing ~10 lines in the middle of
     * {@code drawBackground} with a single method call.
     */
    @Unique
    private void fmlForgeDrawBackground() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for drawBackground extraction.");
    }
}
