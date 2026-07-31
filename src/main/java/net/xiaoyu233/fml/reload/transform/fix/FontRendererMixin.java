package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.client.gui.FontRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

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

    /**
     * Both getCharWidth and renderStringAtPos add 32 to the index returned by
     * ChatAllowedCharacters.allowedCharacters.indexOf. Treat characters beyond
     * the part backed by charWidth as Unicode glyphs instead.
     */
    @Redirect(
            method = "getCharWidth(C)I",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;indexOf(I)I")
    )
    private int fmlGetSafeCharWidthIndex(String allowedCharacters, int character) {
        return fmlGetCharWidthIndex(allowedCharacters, character);
    }

    @Redirect(
            method = "renderStringAtPos(Ljava/lang/String;Z)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;indexOf(I)I")
    )
    private int fmlGetSafeRenderCharIndex(String allowedCharacters, int character) {
        return fmlGetCharWidthIndex(allowedCharacters, character);
    }

    /**
     * Obfuscated text chooses a random allowed-character index and accesses
     * charWidth[index + 32] directly. Restrict that choice to the table-backed
     * portion of the allowed-character list.
     */
    @Redirect(
            method = "renderStringAtPos(Ljava/lang/String;Z)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I")
    )
    private int fmlGetSafeRandomCharIndex(Random random, int bound) {
        return random.nextInt(Math.min(bound, 256 - 32));
    }

    private static int fmlGetCharWidthIndex(String allowedCharacters, int character) {
        int index = allowedCharacters.indexOf(character);
        return index >= 0 && index < 256 - 32 ? index : -1;
    }
}
