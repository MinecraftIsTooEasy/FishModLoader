package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.OpenGlHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link OpenGlHelper}.
 * <p>
 * Adds {@code lastBrightnessX} and {@code lastBrightnessY} static
 * fields that store the last values passed to
 * {@code setLightmapTextureCoords}.
 */
@Mixin(OpenGlHelper.class)
public class OpenGlHelperMixin {

    /**
     * Placeholder: The patch adds two static float fields
     * {@code lastBrightnessX} and {@code lastBrightnessY}, and modifies
     * {@code setLightmapTextureCoords} to store the values when
     * {@code par0 == lightmapTexUnit}.
     * <p>
     * Adding static fields and modifying an existing method's body
     * requires direct patching.
     */
    // Public static Forge fields cannot be contributed by a mixin; keep the
    // placeholders private so this client mixin can still apply.
    @Unique
    private static float lastBrightnessX = 0.0f;

    @Unique
    private static float lastBrightnessY = 0.0f;

    @Unique
    private static void fmlForgeSetLightmapTextureCoords() {
        throw new UnsupportedOperationException(
                "Body modification + static fields required. See patches for OpenGlHelper.");
    }
}
