package net.xiaoyu233.fml.vulkan.mixin.nat.text;

import net.minecraft.GuiTextField;
import net.xiaoyu233.fml.vulkan.render.text.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "text": GuiTextField cursor drawing routed through the native {@link TextRenderer}. */
@Mixin(GuiTextField.class)
public abstract class GuiTextFieldTextMixin {

    /**
     * Replaces GuiTextField.drawCursorVertical (the selection highlight quad) with the native cursor emitter.
     *
     * @author FishModLoader NR-16
     * @reason Native Vulkan text rendering: the MITE GL immediate-mode cursor (colour 0,0,255 with GL_COLOR_LOGIC_OP OR_REVERSE) is
     * emitted by {@link TextRenderer#cursorQuad}. The min/max swap of the original is kept, and the corner order of the original quad
     * (var1,var4),(var3,var4),(var3,var2),(var1,var2) is preserved by the cursorQuad(x0,y0,x1,y1) argument mapping.
     */
    @Overwrite
    private void drawCursorVertical(int var1, int var2, int var3, int var4) {
        if (var1 < var3) {
            int var5 = var1;
            var1 = var3;
            var3 = var5;
        }

        if (var2 < var4) {
            int var6 = var2;
            var2 = var4;
            var4 = var6;
        }

        TextRenderer.cursorQuad(var1, var2, var3, var4);
    }
}
