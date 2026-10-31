package net.xiaoyu233.fml.vulkan.mixin.nat.gui;

import net.minecraft.Gui;
import net.minecraft.Icon;
import net.xiaoyu233.fml.vulkan.render.gui.GuiBatch;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "gui" (NR-17, 3.11): the Gui primitives go through {@link GuiBatch}; string drawing is untouched. */
@Mixin(Gui.class)
public abstract class GuiGuiMixin {
    @Shadow protected float zLevel;

    /** @author NR-17 @reason native renderer: batched untextured quad */
    @Overwrite
    public static void drawRect(int par0, int par1, int par2, int par3, int par4) {
        if (par0 < par2) {
            int t = par0;
            par0 = par2;
            par2 = t;
        }
        if (par1 < par3) {
            int t = par1;
            par1 = par3;
            par3 = t;
        }
        Draw.blend(true);
        Draw.texturing(false);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Draw.color((par4 >> 16 & 0xFF) / 255.0F, (par4 >> 8 & 0xFF) / 255.0F, (par4 & 0xFF) / 255.0F, (par4 >> 24 & 0xFF) / 255.0F);
        // MITE's vertex order (par0,par3) (par2,par3) (par2,par1) (par0,par1) with par0 > par2 and par1 > par3 after the swap
        GuiBatch.rect(par0, par1, par2, par3, 0.0F, par4);
        Draw.texturing(true);
        Draw.blend(false);
    }

    /** @author NR-17 @reason native renderer: batched gradient quad */
    @Overwrite
    protected void drawGradientRect(int par1, int par2, int par3, int par4, int par5, int par6) {
        Draw.texturing(false);
        Draw.blend(true);
        Draw.alphaTest(false);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Draw.smoothShading(true);
        // MITE order: (par3,par2) (par1,par2) (par1,par4) (par3,par4), par5 on the par2 edge: GuiBatch emits y1 edge first, so y0=par4, y1=par2
        GuiBatch.gradient(par3, par4, par1, par2, this.zLevel, par6, par5);
        Draw.smoothShading(false);
        Draw.blend(false);
        Draw.alphaTest(true);
        Draw.texturing(true);
    }

    /** @author NR-17 @reason native renderer: batched textured quad (1/256 uv) */
    @Overwrite
    public void drawTexturedModalRect(int par1, int par2, int par3, int par4, int par5, int par6) {
        float s = 0.00390625F;
        GuiBatch.textured(par1, par2, this.zLevel, par5, par6, par3 * s, par4 * s, (par3 + par5) * s, (par4 + par6) * s);
    }

    /** @author NR-17 @reason native renderer: batched textured quad (full uv) */
    @Overwrite
    public void drawTexturedModalRect2(int par1, int par2, int par5, int par6) {
        GuiBatch.textured(par1, par2, this.zLevel, par5, par6, 0.0F, 0.0F, 1.0F, 1.0F);
    }

    /** @author NR-17 @reason native renderer: batched textured quad from an icon */
    @Overwrite
    public void drawTexturedModelRectFromIcon(int par1, int par2, Icon par3Icon, int par4, int par5) {
        GuiBatch.textured(par1, par2, this.zLevel, par4, par5, par3Icon.getMinU(), par3Icon.getMinV(), par3Icon.getMaxU(), par3Icon.getMaxV());
    }
}
