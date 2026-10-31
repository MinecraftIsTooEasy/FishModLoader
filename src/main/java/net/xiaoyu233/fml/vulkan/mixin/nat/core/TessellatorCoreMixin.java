package net.xiaoyu233.fml.vulkan.mixin.nat.core;

import net.minecraft.GLAllocation;
import net.minecraft.Tessellator;
import net.xiaoyu233.fml.vulkan.render.tess.NativeTess;
import net.xiaoyu233.fml.vulkan.render.tess.TessBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "core": every method of {@code Tessellator} forwards to the {@link NativeTess} of the calling thread (1.6): the render thread's
 * instance, or the mesh worker's own. {@code Tessellator.instance} stays one shared object, so all referencing classes keep working. The
 * instance's own buffers shrink to 64 ints (the fields stay for binary compatibility); the render-thread instance mirrors {@code isDrawing},
 * {@code drawMode} and the offsets into the public fields (5.5).
 */
@Mixin(Tessellator.class)
public abstract class TessellatorCoreMixin {
    /**
     * The constructor allocated 8 MiB of direct memory per instance; the native front-end owns the real buffers. The int[] rawBuffer
     * keeps its full size: RenderGlobal's cloud code (until NR-11) writes into it directly and would overflow a shrunk array.
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/GLAllocation;createDirectByteBuffer(I)Ljava/nio/ByteBuffer;"))
    private java.nio.ByteBuffer fml$shrinkBuffer(int bytes) {
        return GLAllocation.createDirectByteBuffer(256);
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
        tess.mirrorTo((Tessellator) (Object) this);
        return bytes;
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public final void reset() {
        TessBridge.current().reset();
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public final void startDrawingQuads() {
        NativeTess tess = TessBridge.current();
        tess.startDrawingQuads();
        tess.mirrorTo((Tessellator) (Object) this);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public final void startDrawing(int glMode) {
        NativeTess tess = TessBridge.current();
        tess.startDrawing(glMode);
        tess.mirrorTo((Tessellator) (Object) this);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public final void setTextureUV(double u, double v) {
        TessBridge.current().setTextureUV(u, v);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public final void setBrightness(int brightness) {
        TessBridge.current().setBrightness(brightness);
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
    public void setColorOpaque(int r, int g, int b) {
        TessBridge.current().setColorOpaque(r, g, b);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public void setColorRGBA(int r, int g, int b, int a) {
        TessBridge.current().setColorRGBA(r, g, b, a);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public final void addVertexWithUV(double x, double y, double z, double u, double v) {
        TessBridge.current().addVertexWithUV(x, y, z, u, v);
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
    public final void disableColor() {
        TessBridge.current().disableColor();
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public final void setNormal(float x, float y, float z) {
        TessBridge.current().setNormal(x, y, z);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public final void setTranslation(double x, double y, double z) {
        NativeTess tess = TessBridge.current();
        tess.setTranslation(x, y, z);
        tess.mirrorTo((Tessellator) (Object) this);
    }

    /**
     * Forwarding replacement of the original method of the same name.
     *
     * @author fml-native
     * @reason native renderer: forwards to the NativeTess of the calling thread
     */
    @Overwrite
    public final void addTranslation(float x, float y, float z) {
        NativeTess tess = TessBridge.current();
        tess.addTranslation(x, y, z);
        tess.mirrorTo((Tessellator) (Object) this);
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
