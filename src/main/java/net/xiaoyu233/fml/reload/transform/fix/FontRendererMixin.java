package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.client.gui.FontRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontRenderer.class)
public class FontRendererMixin {
    /**
     * MITE uses the character's index in ChatAllowedCharacters as the index of
     * FontRenderer.charWidth. Mods may add Unicode characters to the allowed
     * character list, producing indices outside the 256-entry width table.
     */
    @Inject(method = "renderDefaultChar(IZ)F", at = @At("HEAD"), cancellable = true)
    private void fmlSkipUnsupportedDefaultChar(int charIndex, boolean italic, CallbackInfoReturnable<Float> cir) {
        if (charIndex < 0 || charIndex >= 256) {
            cir.setReturnValue(0.0F);
        }
    }
}
