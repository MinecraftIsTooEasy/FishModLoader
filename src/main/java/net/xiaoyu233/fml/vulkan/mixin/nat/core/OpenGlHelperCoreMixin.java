package net.xiaoyu233.fml.vulkan.mixin.nat.core;

import net.minecraft.OpenGlHelper;
import net.xiaoyu233.fml.vulkan.render.legacy.StateMirror;
import org.lwjgl.opengl.GL13;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Native area "core": texture units are the two {@link Draw} units (0 colour texture, 1 lightmap). The unit constants keep their GL values
 * (33984 / 33985) because game code passes them around; client-side texture state does not exist natively.
 */
@Mixin(OpenGlHelper.class)
public abstract class OpenGlHelperCoreMixin {
    @Shadow
    public static int defaultTexUnit;
    @Shadow
    public static int lightmapTexUnit;
    @Shadow
    private static boolean useMultitextureARB;
    @Shadow
    private static int current_lightmap_texture_coords_param_1;
    @Shadow
    private static float current_lightmap_texture_coords_param_2;
    @Shadow
    private static float current_lightmap_texture_coords_param_3;
    @Shadow
    private static int previous_lightmap_texture_coords_param_1;
    @Shadow
    private static float previous_lightmap_texture_coords_param_2;
    @Shadow
    private static float previous_lightmap_texture_coords_param_3;

    /**
     * @author fml-native
     * @reason native renderer: no GL context capabilities, multitexture always available
     */
    @Overwrite
    public static void initializeTextures() {
        useMultitextureARB = false;
        defaultTexUnit = 33984;
        lightmapTexUnit = 33985;
    }

    /**
     * @author fml-native
     * @reason native renderer: selects the active Draw unit
     */
    @Overwrite
    public static void setActiveTexture(int unit) {
        int index = unit - 33984;
        if (index == 0 || index == 1) {
            Draw.activeUnit(index);
        }
        if (!StateMirror.enabled()) {
            GL13.glActiveTexture(unit); // NR-08 mirror not active: unmigrated sites still use phase-1 GL
        }
    }

    /**
     * @author fml-native
     * @reason native renderer: no client-side vertex arrays
     */
    @Overwrite
    public static void setClientActiveTexture(int unit) {
        if (!StateMirror.enabled()) {
            GL13.glClientActiveTexture(unit);
        }
    }

    /**
     * @author fml-native
     * @reason native renderer: the lightmap coordinate is typed Draw state (previous/current kept for restorePreviousLightmapTextureCoords)
     */
    @Overwrite
    public static void setLightmapTextureCoords(int unit, float u, float v) {
        if (unit == lightmapTexUnit) {
            Draw.light(u, v);
        }
        if (!StateMirror.enabled()) {
            GL13.glMultiTexCoord2f(unit, u, v);
        }
        previous_lightmap_texture_coords_param_1 = current_lightmap_texture_coords_param_1;
        previous_lightmap_texture_coords_param_2 = current_lightmap_texture_coords_param_2;
        previous_lightmap_texture_coords_param_3 = current_lightmap_texture_coords_param_3;
        current_lightmap_texture_coords_param_1 = unit;
        current_lightmap_texture_coords_param_2 = u;
        current_lightmap_texture_coords_param_3 = v;
    }
}
