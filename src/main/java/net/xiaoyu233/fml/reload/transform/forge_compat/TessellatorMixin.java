package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link Tessellator}.
 * <p>
 * The patch makes extensive structural changes:
 * <ul>
 *   <li>Many instance fields become static.</li>
 *   <li>The constructor is gutted (empty or no-arg).</li>
 *   <li>A static initializer replaces the old constructor logic.</li>
 *   <li>{@code draw()} gets chunked drawing.</li>
 *   <li>{@code addVertex()} gets dynamic buffer resizing.</li>
 *   <li>New fields: {@code nativeBufferSize}, {@code trivertsInBuffer},
 *       {@code renderingWorldRenderer}, {@code defaultTexture},
 *       {@code rawBufferSize}, {@code textureID}.</li>
 * </ul>
 */
@Mixin(Tessellator.class)
public class TessellatorMixin {

    @Unique
    private static int nativeBufferSize = 0x200000;

    @Unique
    private static int trivertsInBuffer = (nativeBufferSize / 48) * 6;

    // Forge's public Tessellator.renderingWorldRenderer field cannot be added
    // by Mixin; keep private state so the mixin itself remains applicable.
    @Unique
    private static boolean renderingWorldRenderer = false;

    @Unique
    public boolean defaultTexture = false;

    @Unique
    private int rawBufferSize = 0;

    @Unique
    public int textureID = 0;

    @Unique
    private void fmlForgeTessellatorChanges() {
        throw new UnsupportedOperationException(
                "Extensive structural changes required. See patches for Tessellator.");
    }
}
