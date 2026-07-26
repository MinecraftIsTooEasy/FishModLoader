package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.texture.Stitcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link Stitcher}.
 * <p>
 * The patch contains a single-line bug fix in the
 * {@code doStitchStrips} method:
 * <pre>{@code
 * - flag1 = flag4 && flag2;
 * + flag1 = flag5 && flag3; //Forge: Bug fix: Attempt to fill all downward space before expanding width
 * }</pre>
 */
@Mixin(Stitcher.class)
public class StitcherMixin {

    /**
     * Placeholder: The patch fixes a stitching-ordering bug by
     * swapping operands in a boolean assignment. This is a single-line
     * change inside a complex algorithm and requires direct patching.
     */
    @Unique
    private void fmlForgeStitchBugFix() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for Stitcher bug fix (flag5 && flag3).");
    }
}
