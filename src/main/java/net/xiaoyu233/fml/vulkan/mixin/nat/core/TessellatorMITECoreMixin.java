package net.xiaoyu233.fml.vulkan.mixin.nat.core;

import net.minecraft.TessellatorMITE;
import net.xiaoyu233.fml.vulkan.render.tess.NativeTess;
import net.xiaoyu233.fml.vulkan.render.tess.TessBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Native area "core": the overriding methods of {@code TessellatorMITE} forward to the {@link NativeTess} of the calling thread; the MITE
 * colour packing (unclamped {@code setColorRGBA}) is {@code NativeTess.setColorRGBA_MITE}. See {@link TessellatorCoreMixin}.
 */
@Mixin(TessellatorMITE.class)
public abstract class TessellatorMITECoreMixin {
    /** 8 MiB of direct memory per instance in the original constructor. */
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 8388608))
    private static int fml$shrinkByteBuffer(int original) {
        return 256;
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public int draw() {
        NativeTess tess = TessBridge.current();
        int bytes = tess.draw();
        tess.mirrorTo((TessellatorMITE) (Object) this);
        return bytes;
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public void setColorOpaque_F(float r, float g, float b) {
        TessBridge.current().setColorOpaque_F(r, g, b);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public void setColorRGBA_F(float r, float g, float b, float a) {
        TessBridge.current().setColorRGBA_F(r, g, b, a);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public void setColorRGBA(int r, int g, int b, int a) {
        TessBridge.current().setColorRGBA_MITE(r, g, b, a);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public void addVertex(double x, double y, double z) {
        TessBridge.current().addVertex(x, y, z);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public void setColorOpaque_I(int rgb) {
        TessBridge.current().setColorOpaque_I(rgb);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public void setColorRGBA_I(int rgb, int a) {
        TessBridge.current().setColorRGBA_I(rgb, a);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public void add4VerticesWithUV(double[] x, double[] y, double[] z, double[] u, double[] v) {
        TessBridge.current().add4VerticesWithUV(x, y, z, u, v);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public void add4VerticesWithUVandAO(double[] x, double[] y, double[] z, double[] u, double[] v, float[] r, float[] g, float[] b, int[] brightness) {
        TessBridge.current().add4VerticesWithUVandAO(x, y, z, u, v, r, g, b, brightness);
    }
}
