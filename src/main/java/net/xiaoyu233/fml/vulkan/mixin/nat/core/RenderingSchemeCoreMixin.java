package net.xiaoyu233.fml.vulkan.mixin.nat.core;

import net.minecraft.Minecraft;
import net.minecraft.RenderingScheme;
import net.minecraft.Tessellator;
import net.minecraft.TessellatorMITE;
import net.xiaoyu233.fml.vulkan.render.tess.NativeTess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Native area "core": the rendering scheme only selects the colour packing of {@code NativeTess} (scheme 0 / 102 = vanilla clamped
 * packing, otherwise MITE's); the {@code Tessellator} instance is created as before, but it is a thin forwarding shell now (its buffers
 * are shrunk, see {@link TessellatorCoreMixin}).
 */
@Mixin(RenderingScheme.class)
public abstract class RenderingSchemeCoreMixin {
    @Shadow
    public static int current;

    @Shadow
    public static String getSchemeDescriptor(int schemeIndex) {
        throw new AssertionError();
    }

    /**
     * @author fml-native
     * @reason native renderer: also tells NativeTess which colour packing applies
     */
    @Overwrite
    public static void setCurrent(int schemeIndex) {
        if (getSchemeDescriptor(schemeIndex) == null) {
            if (Minecraft.theMinecraft != null) {
                Minecraft.theMinecraft.getLogAgent().logWarning("Invalid rendering scheme (" + schemeIndex + "), reverting to " + getSchemeDescriptor(1) + " (" + 1 + ")");
            }
            schemeIndex = 1;
        } else {
            Minecraft.theMinecraft.getLogAgent().logInfo("Rendering scheme: " + getSchemeDescriptor(schemeIndex));
        }
        current = schemeIndex;
        NativeTess.setScheme(schemeIndex);
        Tessellator.instance = current == 0 ? new Tessellator() : new TessellatorMITE();
    }
}
