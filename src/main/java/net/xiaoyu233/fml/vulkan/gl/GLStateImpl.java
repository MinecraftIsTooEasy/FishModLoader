package net.xiaoyu233.fml.vulkan.gl;

import net.xiaoyu233.fml.vulkan.render.legacy.LegacyInterop;
import net.xiaoyu233.fml.vulkan.render.legacy.StateMirror;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.gl.draw.ClientState;
import net.xiaoyu233.fml.vulkan.gl.draw.DisplayLists;
import net.xiaoyu233.fml.vulkan.gl.draw.DrawSystem;
import net.xiaoyu233.fml.vulkan.gl.state.GLState;
import net.xiaoyu233.fml.vulkan.pipeline.VertexLayout;
import net.xiaoyu233.fml.vulkan.texture.TextureManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * GL entry points owned by the state machine (module D1): the LWJGL 2 facades in {@code org.lwjgl.opengl} delegate here with the
 * exact LWJGL 2 descriptors. This class owns the facade semantics (render thread check, display list recording, LWJGL buffer
 * conventions: absolute reads/writes that never move the buffer position, enum validation); the state itself is
 * {@link GLState}.
 *
 * <p>Display lists (ARCHITECTURE 5.2): every state changing function is written as
 * {@code if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().foo(a))) return; GLState.facade().foo(a);} -
 * the recorded closure calls the internal setter, never the public facade, and Buffer arguments are copied at call time.
 * Current-attribute functions additionally report through {@code DisplayLists.compileAttrib}.
 */
@SuppressWarnings("unused")
public final class GLStateImpl {
    // render thread only, so shared scratch storage is safe and keeps the hot paths allocation free
    private static final float[] F4 = new float[4];
    private static final float[] MAT16 = new float[16];
    private static final double[] SCRATCH_D = new double[16];
    private static final IntBuffer SCRATCH_INT = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();

    private GLStateImpl() {
    }

    private static boolean renderThreadCheck() {
        return GLState.checkRenderThread();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** LWJGL 2 BufferChecks: a buffer that is too small is an IllegalArgumentException. */
    private static void needRemaining(Buffer b, int n) {
        if (b.remaining() < n) {
            throw new IllegalArgumentException("Number of remaining buffer elements is " + b.remaining() + ", must be at least "
                    + n + ". Because at most " + n + " elements can be returned, a buffer with at least " + n
                    + " elements is required, regardless of actual returned element count");
        }
    }

    /** GL integer color/direction conversion: c = (2i + 1) / (2^32 - 1), covering [-1, 1]. */
    private static float intToColor(int i) {
        return (float) Math.max(-1.0, Math.min(1.0, (2.0 * i + 1.0) / 4294967295.0));
    }

    /**
     * Float state returned by an integer query. Color-like values (colors, normals, depth range / clear value, alpha reference)
     * map [-1, 1] linearly onto the int range by truncation, {@code (int) (f * (2^31 - 1))}, which is what the Apple GL 2.1
     * driver returns for all of them (0.3 reads back as 644245119, -0.5 as -1073741823); everything else is rounded to the
     * nearest integer, halves away from zero (also the driver's behaviour). Out-of-range results saturate.
     */
    private static int toInt(double v, boolean colorLike) {
        final long r = colorLike ? (long) (v * 2147483647.0) : (v < 0.0 ? -Math.round(-v) : Math.round(v));
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, r));
    }

    /** Reads n floats starting at the buffer position without moving it. */
    private static float[] read(FloatBuffer b, int n) {
        needRemaining(b, n);
        final int p = b.position();
        final float[] v = F4;
        for (int i = 0; i < n; i++) {
            v[i] = b.get(p + i);
        }
        return v;
    }

    private static float[] read(DoubleBuffer b, int n) {
        needRemaining(b, n);
        final int p = b.position();
        final float[] v = F4;
        for (int i = 0; i < n; i++) {
            v[i] = (float) b.get(p + i);
        }
        return v;
    }

    private static float[] read(IntBuffer b, int n, boolean color) {
        needRemaining(b, n);
        final int p = b.position();
        final float[] v = F4;
        for (int i = 0; i < n; i++) {
            final int x = b.get(p + i);
            v[i] = color ? intToColor(x) : (float) x;
        }
        return v;
    }

    private static float[] one(float value) {
        F4[0] = value;
        return F4;
    }

    private static float[] readMatrix(FloatBuffer m, boolean transpose) {
        needRemaining(m, 16);
        final int p = m.position();
        for (int i = 0; i < 16; i++) {
            MAT16[i] = m.get(p + i);
        }
        return transpose ? transposed(MAT16) : MAT16;
    }

    private static float[] readMatrix(DoubleBuffer m, boolean transpose) {
        needRemaining(m, 16);
        final int p = m.position();
        for (int i = 0; i < 16; i++) {
            MAT16[i] = (float) m.get(p + i);
        }
        return transpose ? transposed(MAT16) : MAT16;
    }

    private static float[] transposed(float[] m) {
        for (int c = 0; c < 4; c++) {
            for (int r = c + 1; r < 4; r++) {
                final float t = m[c * 4 + r];
                m[c * 4 + r] = m[r * 4 + c];
                m[r * 4 + c] = t;
            }
        }
        return m;
    }

    private static void loadMatrix(float[] m) {
        if (DisplayLists.isCompiling()) {
            final float[] copy = m.clone();
            if (!DisplayLists.record(() -> GLState.facade().loadMatrix(copy))) {
                return;
            }
        }
        GLState.facade().loadMatrix(m);
    }

    private static void multMatrix(float[] m) {
        if (DisplayLists.isCompiling()) {
            final float[] copy = m.clone();
            if (!DisplayLists.record(() -> GLState.facade().multMatrix(copy))) {
                return;
            }
        }
        GLState.facade().multMatrix(m);
    }

    private static void color(float r, float g, float b, float a) {
        if (DisplayLists.isCompiling()) {
            DisplayLists.compileAttrib(VertexLayout.ATTR_COLOR, r, g, b, a);
            if (!DisplayLists.record(() -> GLState.facade().setColor(r, g, b, a))) {
                return;
            }
        }
        GLState.facade().setColor(r, g, b, a);
    }

    private static void normal(float x, float y, float z) {
        if (DisplayLists.isCompiling()) {
            DisplayLists.compileAttrib(VertexLayout.ATTR_NORMAL, x, y, z, 1f);
            if (!DisplayLists.record(() -> GLState.facade().setNormal(x, y, z))) {
                return;
            }
        }
        GLState.facade().setNormal(x, y, z);
    }

    private static void texCoord(float s, float t, float r, float q) {
        if (DisplayLists.isCompiling()) {
            DisplayLists.compileAttrib(VertexLayout.ATTR_TEX0, s, t, r, q);
            if (!DisplayLists.record(() -> GLState.facade().setTexCoord(0, s, t, r, q))) {
                return;
            }
        }
        GLState.facade().setTexCoord(0, s, t, r, q);
    }

    private static void multiTexCoord(int target, float s, float t, float r, float q) {
        final int unit = target - GL13.GL_TEXTURE0;
        if (unit < 0 || unit >= GLState.MAX_TEXTURE_UNITS) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (DisplayLists.isCompiling()) {
            // the captured immediate-mode layout only has slots for unit 0 and unit 1
            if (unit == 0) {
                DisplayLists.compileAttrib(VertexLayout.ATTR_TEX0, s, t, r, q);
            } else if (unit == 1) {
                DisplayLists.compileAttrib(VertexLayout.ATTR_TEX1, s, t, r, q);
            }
            if (!DisplayLists.record(() -> GLState.facade().setTexCoord(unit, s, t, r, q))) {
                return;
            }
        }
        GLState.facade().setTexCoord(unit, s, t, r, q);
    }

    private static void fog(int pname, float[] v, int n) {
        if (DisplayLists.isCompiling()) {
            final float[] copy = Arrays.copyOf(v, n);
            if (!DisplayLists.record(() -> GLState.facade().setFog(pname, copy))) {
                return;
            }
        }
        GLState.facade().setFog(pname, v);
    }

    private static void light(int light, int pname, float[] v, int n) {
        final int index = light - GL11.GL_LIGHT0;
        if (index < 0 || index >= GLState.MAX_LIGHTS) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (DisplayLists.isCompiling()) {
            final float[] copy = Arrays.copyOf(v, n);
            if (!DisplayLists.record(() -> GLState.facade().setLight(index, pname, copy))) {
                return;
            }
        }
        GLState.facade().setLight(index, pname, v);
    }

    private static void lightModel(int pname, float[] v, int n) {
        if (DisplayLists.isCompiling()) {
            final float[] copy = Arrays.copyOf(v, n);
            if (!DisplayLists.record(() -> GLState.facade().setLightModel(pname, copy))) {
                return;
            }
        }
        GLState.facade().setLightModel(pname, v);
    }

    private static void material(int face, int pname, float[] v, int n) {
        if (DisplayLists.isCompiling()) {
            final float[] copy = Arrays.copyOf(v, n);
            if (!DisplayLists.record(() -> GLState.facade().setMaterial(face, pname, copy))) {
                return;
            }
        }
        GLState.facade().setMaterial(face, pname, v);
    }

    private static void texEnv(int target, int pname, float[] v, int n) {
        if (DisplayLists.isCompiling()) {
            final float[] copy = Arrays.copyOf(v, n);
            if (!DisplayLists.record(() -> GLState.facade().setTexEnv(target, pname, copy))) {
                return;
            }
        }
        GLState.facade().setTexEnv(target, pname, v);
    }

    private static void texGen(int coord, int pname, float[] v, int n) {
        if (DisplayLists.isCompiling()) {
            final float[] copy = Arrays.copyOf(v, n);
            if (!DisplayLists.record(() -> GLState.facade().setTexGen(coord, pname, copy))) {
                return;
            }
        }
        GLState.facade().setTexGen(coord, pname, v);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // texture unit, enable/disable
    // ---------------------------------------------------------------------------------------------------------------

    public static void glActiveTexture(int texture) {
        if (!renderThreadCheck()) return;
        final int unit = texture - GL13.GL_TEXTURE0;
        if (unit < 0 || unit >= GLState.MAX_TEXTURE_UNITS) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setActiveTexture(unit))) return;
        GLState.facade().setActiveTexture(unit);
    }

    public static void glEnable(int cap) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setEnable(cap, true))) return;
        GLState.facade().setEnable(cap, true);
    }

    public static void glDisable(int cap) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setEnable(cap, false))) return;
        GLState.facade().setEnable(cap, false);
    }

    public static boolean glIsEnabled(int cap) {
        if (!renderThreadCheck()) return false;
        if (StateMirror.nativeDirty != 0) StateMirror.syncNativeToLegacy();
        final Boolean client = ClientState.isEnabled(cap);
        if (client != null) {
            return client;
        }
        return GLState.facade().isEnabled(cap);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // blending, alpha test, logic op
    // ---------------------------------------------------------------------------------------------------------------

    public static void glAlphaFunc(int func, float ref) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setAlphaFunc(func, ref))) return;
        GLState.facade().setAlphaFunc(func, ref);
    }

    public static void glBlendColor(float red, float green, float blue, float alpha) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setBlendColor(red, green, blue, alpha))) return;
        GLState.facade().setBlendColor(red, green, blue, alpha);
    }

    public static void glBlendEquation(int mode) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setBlendEquation(mode))) return;
        GLState.facade().setBlendEquation(mode);
    }

    public static void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setBlendEquationSeparate(modeRGB, modeAlpha))) return;
        GLState.facade().setBlendEquationSeparate(modeRGB, modeAlpha);
    }

    public static void glBlendFunc(int sfactor, int dfactor) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setBlendFunc(sfactor, dfactor))) return;
        GLState.facade().setBlendFunc(sfactor, dfactor);
    }

    public static void glBlendFuncSeparate(int sfactorRGB, int dfactorRGB, int sfactorAlpha, int dfactorAlpha) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(
                () -> GLState.facade().setBlendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha))) return;
        GLState.facade().setBlendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
    }

    public static void glLogicOp(int opcode) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setLogicOp(opcode))) return;
        GLState.facade().setLogicOp(opcode);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // clear, buffers, masks
    // ---------------------------------------------------------------------------------------------------------------

    public static void glClear(int mask) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().clear(mask))) return;
        // native batches queued before the clear must be recorded first, and pending native state pulled before the clear reads it
        LegacyInterop.beforeEmulatedDraw();
        GLState.facade().clear(mask);
    }

    public static void glClearColor(float red, float green, float blue, float alpha) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setClearColor(red, green, blue, alpha))) return;
        GLState.facade().setClearColor(red, green, blue, alpha);
    }

    public static void glClearDepth(double depth) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setClearDepth(depth))) return;
        GLState.facade().setClearDepth(depth);
    }

    public static void glClearStencil(int s) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setClearStencil(s))) return;
        GLState.facade().setClearStencil(s);
    }

    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setColorMask(red, green, blue, alpha))) return;
        GLState.facade().setColorMask(red, green, blue, alpha);
    }

    public static void glDrawBuffer(int buf) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setDrawBuffer(buf))) return;
        GLState.facade().setDrawBuffer(buf);
    }

    public static void glReadBuffer(int src) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setReadBuffer(src))) return;
        GLState.facade().setReadBuffer(src);
    }

    public static void glDepthFunc(int func) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setDepthFunc(func))) return;
        GLState.facade().setDepthFunc(func);
    }

    public static void glDepthMask(boolean flag) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setDepthMask(flag))) return;
        GLState.facade().setDepthMask(flag);
    }

    public static void glDepthRange(double n, double f) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setDepthRange(n, f))) return;
        GLState.facade().setDepthRange(n, f);
    }

    public static void glStencilFunc(int func, int ref, int mask) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setStencilFunc(func, ref, mask))) return;
        GLState.facade().setStencilFunc(func, ref, mask);
    }

    public static void glStencilMask(int mask) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setStencilMask(mask))) return;
        GLState.facade().setStencilMask(mask);
    }

    public static void glStencilOp(int fail, int zfail, int zpass) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setStencilOp(fail, zfail, zpass))) return;
        GLState.facade().setStencilOp(fail, zfail, zpass);
    }

    public static void glFinish() {
        if (!renderThreadCheck()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.flush();
        RenderCore.flushAndWait();
    }

    public static void glFlush() {
        if (!renderThreadCheck()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.flush();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // current color / normal / texture coordinates
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Signed bytes map to [-1, 1] with c = max(b / 127, -1): what every driver does for glColor*b / glNormal3b and what the
     * R8G8B8A8_SNORM fetch of GL_BYTE normal arrays yields, so a normal is the same through glNormal3b and glNormalPointer
     * (the GL 1.5 table 2.9 formula (2b + 1) / 255 would turn 0 into 0.0039 and -127 into -0.992).
     */
    private static float signedByte(byte b) {
        return Math.max(b / 127f, -1f);
    }

    private static float unsignedByte(byte b) {
        return (b & 0xFF) / 255f;
    }

    public static void glColor3b(byte red, byte green, byte blue) {
        if (!renderThreadCheck()) return;
        color(signedByte(red), signedByte(green), signedByte(blue), 1f);
    }

    public static void glColor3d(double red, double green, double blue) {
        if (!renderThreadCheck()) return;
        color((float) red, (float) green, (float) blue, 1f);
    }

    public static void glColor3f(float red, float green, float blue) {
        if (!renderThreadCheck()) return;
        color(red, green, blue, 1f);
    }

    public static void glColor3ub(byte red, byte green, byte blue) {
        if (!renderThreadCheck()) return;
        color(unsignedByte(red), unsignedByte(green), unsignedByte(blue), 1f);
    }

    public static void glColor4b(byte red, byte green, byte blue, byte alpha) {
        if (!renderThreadCheck()) return;
        color(signedByte(red), signedByte(green), signedByte(blue), signedByte(alpha));
    }

    public static void glColor4d(double red, double green, double blue, double alpha) {
        if (!renderThreadCheck()) return;
        color((float) red, (float) green, (float) blue, (float) alpha);
    }

    public static void glColor4f(float red, float green, float blue, float alpha) {
        if (!renderThreadCheck()) return;
        color(red, green, blue, alpha);
    }

    public static void glColor4ub(byte red, byte green, byte blue, byte alpha) {
        if (!renderThreadCheck()) return;
        color(unsignedByte(red), unsignedByte(green), unsignedByte(blue), unsignedByte(alpha));
    }

    public static void glNormal3b(byte nx, byte ny, byte nz) {
        if (!renderThreadCheck()) return;
        normal(signedByte(nx), signedByte(ny), signedByte(nz));
    }

    public static void glNormal3d(double nx, double ny, double nz) {
        if (!renderThreadCheck()) return;
        normal((float) nx, (float) ny, (float) nz);
    }

    public static void glNormal3f(float nx, float ny, float nz) {
        if (!renderThreadCheck()) return;
        normal(nx, ny, nz);
    }

    public static void glNormal3i(int nx, int ny, int nz) {
        if (!renderThreadCheck()) return;
        normal(intToColor(nx), intToColor(ny), intToColor(nz));
    }

    public static void glTexCoord1d(double s) {
        if (!renderThreadCheck()) return;
        texCoord((float) s, 0f, 0f, 1f);
    }

    public static void glTexCoord1f(float s) {
        if (!renderThreadCheck()) return;
        texCoord(s, 0f, 0f, 1f);
    }

    public static void glTexCoord2d(double s, double t) {
        if (!renderThreadCheck()) return;
        texCoord((float) s, (float) t, 0f, 1f);
    }

    public static void glTexCoord2f(float s, float t) {
        if (!renderThreadCheck()) return;
        texCoord(s, t, 0f, 1f);
    }

    public static void glTexCoord3d(double s, double t, double r) {
        if (!renderThreadCheck()) return;
        texCoord((float) s, (float) t, (float) r, 1f);
    }

    public static void glTexCoord3f(float s, float t, float r) {
        if (!renderThreadCheck()) return;
        texCoord(s, t, r, 1f);
    }

    public static void glTexCoord4d(double s, double t, double r, double q) {
        if (!renderThreadCheck()) return;
        texCoord((float) s, (float) t, (float) r, (float) q);
    }

    public static void glTexCoord4f(float s, float t, float r, float q) {
        if (!renderThreadCheck()) return;
        texCoord(s, t, r, q);
    }

    public static void glMultiTexCoord1d(int target, double s) {
        if (!renderThreadCheck()) return;
        multiTexCoord(target, (float) s, 0f, 0f, 1f);
    }

    public static void glMultiTexCoord1f(int target, float s) {
        if (!renderThreadCheck()) return;
        multiTexCoord(target, s, 0f, 0f, 1f);
    }

    public static void glMultiTexCoord2d(int target, double s, double t) {
        if (!renderThreadCheck()) return;
        multiTexCoord(target, (float) s, (float) t, 0f, 1f);
    }

    public static void glMultiTexCoord2f(int target, float s, float t) {
        if (!renderThreadCheck()) return;
        multiTexCoord(target, s, t, 0f, 1f);
    }

    public static void glMultiTexCoord3d(int target, double s, double t, double r) {
        if (!renderThreadCheck()) return;
        multiTexCoord(target, (float) s, (float) t, (float) r, 1f);
    }

    public static void glMultiTexCoord3f(int target, float s, float t, float r) {
        if (!renderThreadCheck()) return;
        multiTexCoord(target, s, t, r, 1f);
    }

    public static void glMultiTexCoord4d(int target, double s, double t, double r, double q) {
        if (!renderThreadCheck()) return;
        multiTexCoord(target, (float) s, (float) t, (float) r, (float) q);
    }

    public static void glMultiTexCoord4f(int target, float s, float t, float r, float q) {
        if (!renderThreadCheck()) return;
        multiTexCoord(target, s, t, r, q);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // rasterization state, viewport, scissor
    // ---------------------------------------------------------------------------------------------------------------

    public static void glCullFace(int mode) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setCullFace(mode))) return;
        GLState.facade().setCullFace(mode);
    }

    public static void glFrontFace(int mode) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setFrontFace(mode))) return;
        GLState.facade().setFrontFace(mode);
    }

    public static void glPolygonMode(int face, int mode) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setPolygonMode(face, mode))) return;
        GLState.facade().setPolygonMode(face, mode);
    }

    public static void glPolygonOffset(float factor, float units) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setPolygonOffset(factor, units))) return;
        GLState.facade().setPolygonOffset(factor, units);
    }

    public static void glLineWidth(float width) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setLineWidth(width))) return;
        GLState.facade().setLineWidth(width);
    }

    public static void glPointSize(float size) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setPointSize(size))) return;
        GLState.facade().setPointSize(size);
    }

    public static void glShadeModel(int mode) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setShadeModel(mode))) return;
        GLState.facade().setShadeModel(mode);
    }

    public static void glViewport(int x, int y, int width, int height) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setViewport(x, y, width, height))) return;
        GLState.facade().setViewport(x, y, width, height);
    }

    public static void glScissor(int x, int y, int width, int height) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setScissor(x, y, width, height))) return;
        GLState.facade().setScissor(x, y, width, height);
    }

    public static void glHint(int target, int mode) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setHint(target, mode))) return;
        GLState.facade().setHint(target, mode);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // matrices
    // ---------------------------------------------------------------------------------------------------------------

    public static void glMatrixMode(int mode) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setMatrixMode(mode))) return;
        GLState.facade().setMatrixMode(mode);
    }

    public static void glPushMatrix() {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().pushMatrix())) return;
        GLState.facade().pushMatrix();
    }

    public static void glPopMatrix() {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().popMatrix())) return;
        GLState.facade().popMatrix();
    }

    public static void glLoadIdentity() {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().loadIdentity())) return;
        GLState.facade().loadIdentity();
    }

    public static void glLoadMatrix(DoubleBuffer m) {
        if (!renderThreadCheck()) return;
        loadMatrix(readMatrix(m, false));
    }

    public static void glLoadMatrix(FloatBuffer m) {
        if (!renderThreadCheck()) return;
        loadMatrix(readMatrix(m, false));
    }

    public static void glLoadTransposeMatrix(DoubleBuffer m) {
        if (!renderThreadCheck()) return;
        loadMatrix(readMatrix(m, true));
    }

    public static void glLoadTransposeMatrix(FloatBuffer m) {
        if (!renderThreadCheck()) return;
        loadMatrix(readMatrix(m, true));
    }

    public static void glMultMatrix(DoubleBuffer m) {
        if (!renderThreadCheck()) return;
        multMatrix(readMatrix(m, false));
    }

    public static void glMultMatrix(FloatBuffer m) {
        if (!renderThreadCheck()) return;
        multMatrix(readMatrix(m, false));
    }

    public static void glMultTransposeMatrix(DoubleBuffer m) {
        if (!renderThreadCheck()) return;
        multMatrix(readMatrix(m, true));
    }

    public static void glMultTransposeMatrix(FloatBuffer m) {
        if (!renderThreadCheck()) return;
        multMatrix(readMatrix(m, true));
    }

    public static void glTranslated(double x, double y, double z) {
        if (!renderThreadCheck()) return;
        glTranslatef((float) x, (float) y, (float) z);
    }

    public static void glTranslatef(float x, float y, float z) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().translate(x, y, z))) return;
        GLState.facade().translate(x, y, z);
    }

    public static void glScaled(double x, double y, double z) {
        if (!renderThreadCheck()) return;
        glScalef((float) x, (float) y, (float) z);
    }

    public static void glScalef(float x, float y, float z) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().scale(x, y, z))) return;
        GLState.facade().scale(x, y, z);
    }

    public static void glRotated(double angle, double x, double y, double z) {
        if (!renderThreadCheck()) return;
        glRotatef((float) angle, (float) x, (float) y, (float) z);
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().rotate(angle, x, y, z))) return;
        GLState.facade().rotate(angle, x, y, z);
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().ortho(left, right, bottom, top, zNear, zFar))) return;
        GLState.facade().ortho(left, right, bottom, top, zNear, zFar);
    }

    public static void glFrustum(double left, double right, double bottom, double top, double zNear, double zFar) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().frustum(left, right, bottom, top, zNear, zFar))) return;
        GLState.facade().frustum(left, right, bottom, top, zNear, zFar);
    }

    public static void glPushAttrib(int mask) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().pushAttrib(mask))) return;
        GLState.facade().pushAttrib(mask);
    }

    public static void glPopAttrib() {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().popAttrib())) return;
        GLState.facade().popAttrib();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // fog
    // ---------------------------------------------------------------------------------------------------------------

    public static void glFog(int pname, FloatBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.fogParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        fog(pname, read(param, n), n);
    }

    public static void glFog(int pname, IntBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.fogParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        fog(pname, read(param, n, GLState.fogParamIsColor(pname)), n);
    }

    public static void glFogf(int pname, float param) {
        if (!renderThreadCheck()) return;
        if (GLState.fogParamCount(pname) != 1) { // unknown pname, or GL_FOG_COLOR which needs a vector
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        fog(pname, one(param), 1);
    }

    public static void glFogi(int pname, int param) {
        if (!renderThreadCheck()) return;
        if (GLState.fogParamCount(pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        fog(pname, one(param), 1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // lighting
    // ---------------------------------------------------------------------------------------------------------------

    public static void glLight(int light, int pname, FloatBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.lightParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        light(light, pname, read(param, n), n);
    }

    public static void glLight(int light, int pname, IntBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.lightParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        light(light, pname, read(param, n, GLState.lightParamIsColor(pname)), n);
    }

    public static void glLightf(int light, int pname, float param) {
        if (!renderThreadCheck()) return;
        if (GLState.lightParamCount(pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        light(light, pname, one(param), 1);
    }

    public static void glLighti(int light, int pname, int param) {
        if (!renderThreadCheck()) return;
        if (GLState.lightParamCount(pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        light(light, pname, one(param), 1);
    }

    public static void glLightModel(int pname, FloatBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.lightModelParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        lightModel(pname, read(param, n), n);
    }

    public static void glLightModel(int pname, IntBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.lightModelParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        lightModel(pname, read(param, n, pname == GL11.GL_LIGHT_MODEL_AMBIENT), n);
    }

    public static void glLightModelf(int pname, float param) {
        if (!renderThreadCheck()) return;
        if (GLState.lightModelParamCount(pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        lightModel(pname, one(param), 1);
    }

    public static void glLightModeli(int pname, int param) {
        if (!renderThreadCheck()) return;
        if (GLState.lightModelParamCount(pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        lightModel(pname, one(param), 1);
    }

    public static void glMaterial(int face, int pname, FloatBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.materialParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        material(face, pname, read(param, n), n);
    }

    public static void glMaterial(int face, int pname, IntBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.materialParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        material(face, pname, read(param, n, GLState.materialParamIsColor(pname)), n);
    }

    public static void glMaterialf(int face, int pname, float param) {
        if (!renderThreadCheck()) return;
        if (GLState.materialParamCount(pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        material(face, pname, one(param), 1);
    }

    public static void glMateriali(int face, int pname, int param) {
        if (!renderThreadCheck()) return;
        if (GLState.materialParamCount(pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        material(face, pname, one(param), 1);
    }

    public static void glColorMaterial(int face, int mode) {
        if (!renderThreadCheck()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> GLState.facade().setColorMaterial(face, mode))) return;
        GLState.facade().setColorMaterial(face, mode);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // texture environment and coordinate generation
    // ---------------------------------------------------------------------------------------------------------------

    public static void glTexEnv(int target, int pname, FloatBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.texEnvParamCount(target, pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        texEnv(target, pname, read(param, n), n);
    }

    public static void glTexEnv(int target, int pname, IntBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.texEnvParamCount(target, pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        texEnv(target, pname, read(param, n, GLState.texEnvParamIsColor(target, pname)), n);
    }

    public static void glTexEnvf(int target, int pname, float param) {
        if (!renderThreadCheck()) return;
        if (GLState.texEnvParamCount(target, pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        texEnv(target, pname, one(param), 1);
    }

    public static void glTexEnvi(int target, int pname, int param) {
        if (!renderThreadCheck()) return;
        if (GLState.texEnvParamCount(target, pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        texEnv(target, pname, one(param), 1);
    }

    public static void glTexGen(int coord, int pname, DoubleBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.texGenParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        texGen(coord, pname, read(param, n), n);
    }

    public static void glTexGen(int coord, int pname, FloatBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.texGenParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        texGen(coord, pname, read(param, n), n);
    }

    public static void glTexGen(int coord, int pname, IntBuffer param) {
        if (!renderThreadCheck()) return;
        final int n = GLState.texGenParamCount(pname);
        if (n == 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        texGen(coord, pname, read(param, n, false), n);
    }

    public static void glTexGend(int coord, int pname, double param) {
        if (!renderThreadCheck()) return;
        if (GLState.texGenParamCount(pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        texGen(coord, pname, one((float) param), 1);
    }

    public static void glTexGenf(int coord, int pname, float param) {
        if (!renderThreadCheck()) return;
        if (GLState.texGenParamCount(pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        texGen(coord, pname, one(param), 1);
    }

    public static void glTexGeni(int coord, int pname, int param) {
        if (!renderThreadCheck()) return;
        if (GLState.texGenParamCount(pname) != 1) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        texGen(coord, pname, one(param), 1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // queries
    // ---------------------------------------------------------------------------------------------------------------

    public static int glGetError() {
        if (!renderThreadCheck()) return GL11.GL_NO_ERROR;
        return GLErrors.getAndClear();
    }

    /**
     * Fills {@code SCRATCH_D} with the values of {@code pname} and returns their number. D1 owns most state variables; the
     * texture module (TEXTURE_BINDING_2D, MAX_TEXTURE_SIZE, pixel store) and the client state (array bindings, list state) own
     * the rest. -1 means unknown: GL_INVALID_ENUM has been raised.
     */
    private static int queryValues(int pname) {
        if (StateMirror.nativeDirty != 0) StateMirror.syncNativeToLegacy();
        final int n = GLState.facade().query(pname, SCRATCH_D);
        if (n >= 0) {
            return n;
        }
        final IntBuffer out = SCRATCH_INT;
        out.clear();
        out.put(0, 0);
        if (TextureManager.getIntegerv(pname, out) || ClientState.getIntegerv(pname, out)) {
            SCRATCH_D[0] = out.get(0);
            return 1;
        }
        GLErrors.set(GL11.GL_INVALID_ENUM);
        return -1;
    }

    public static boolean glGetBoolean(int pname) {
        if (!renderThreadCheck()) return false;
        return queryValues(pname) > 0 && SCRATCH_D[0] != 0.0;
    }

    public static void glGetBoolean(int pname, ByteBuffer data) {
        if (!renderThreadCheck()) return;
        final int n = queryValues(pname);
        if (n < 0) return;
        needRemaining(data, n);
        final int p = data.position();
        for (int i = 0; i < n; i++) {
            data.put(p + i, SCRATCH_D[i] != 0.0 ? (byte) 1 : (byte) 0);
        }
    }

    public static float glGetFloat(int pname) {
        if (!renderThreadCheck()) return 0f;
        return queryValues(pname) > 0 ? (float) SCRATCH_D[0] : 0f;
    }

    public static void glGetFloat(int pname, FloatBuffer data) {
        if (!renderThreadCheck()) return;
        final int n = queryValues(pname);
        if (n < 0) return;
        needRemaining(data, n);
        final int p = data.position(); // absolute puts: LWJGL 2 never moves the position of a glGet* target
        for (int i = 0; i < n; i++) {
            data.put(p + i, (float) SCRATCH_D[i]);
        }
    }

    public static double glGetDouble(int pname) {
        if (!renderThreadCheck()) return 0.0;
        return queryValues(pname) > 0 ? SCRATCH_D[0] : 0.0;
    }

    public static void glGetDouble(int pname, DoubleBuffer data) {
        if (!renderThreadCheck()) return;
        final int n = queryValues(pname);
        if (n < 0) return;
        needRemaining(data, n);
        final int p = data.position();
        for (int i = 0; i < n; i++) {
            data.put(p + i, SCRATCH_D[i]);
        }
    }

    public static int glGetInteger(int pname) {
        if (!renderThreadCheck()) return 0;
        return queryValues(pname) > 0 ? toInt(SCRATCH_D[0], GLState.isColorLikeState(pname)) : 0;
    }

    public static void glGetInteger(int pname, IntBuffer data) {
        if (!renderThreadCheck()) return;
        final int n = queryValues(pname);
        if (n < 0) return;
        needRemaining(data, n);
        final boolean color = GLState.isColorLikeState(pname);
        final int p = data.position();
        for (int i = 0; i < n; i++) {
            data.put(p + i, toInt(SCRATCH_D[i], color));
        }
    }

    // indexed state queries (GL 3.0 / ARB_uniform_buffer_object) have no fixed-function state behind them
    public static boolean glGetBoolean(int pname, int data) {
        if (!renderThreadCheck()) return false;
        GLUnsupported.call("glGetBooleani_v");
        return false;
    }

    public static void glGetBoolean(int a0, int a1, ByteBuffer a2) {
        if (!renderThreadCheck()) return;
        GLUnsupported.call("glGetBooleani_v");
    }

    public static int glGetInteger(int pname, int data) {
        if (!renderThreadCheck()) return 0;
        GLUnsupported.call("glGetIntegeri_v");
        return 0;
    }

    public static void glGetInteger(int a0, int a1, IntBuffer a2) {
        if (!renderThreadCheck()) return;
        GLUnsupported.call("glGetIntegeri_v");
    }

    private static final float[] QUERY_F = new float[4];

    public static void glGetLight(int light, int pname, FloatBuffer params) {
        if (!renderThreadCheck()) return;
        final int n = GLState.facade().queryLight(light - GL11.GL_LIGHT0, pname, QUERY_F);
        if (n < 0) return;
        needRemaining(params, n);
        final int p = params.position();
        for (int i = 0; i < n; i++) {
            params.put(p + i, QUERY_F[i]);
        }
    }

    public static void glGetLight(int light, int pname, IntBuffer params) {
        if (!renderThreadCheck()) return;
        final int n = GLState.facade().queryLight(light - GL11.GL_LIGHT0, pname, QUERY_F);
        if (n < 0) return;
        needRemaining(params, n);
        final boolean color = GLState.lightParamIsColor(pname);
        final int p = params.position();
        for (int i = 0; i < n; i++) {
            params.put(p + i, toInt(QUERY_F[i], color));
        }
    }

    public static void glGetMaterial(int face, int pname, FloatBuffer params) {
        if (!renderThreadCheck()) return;
        final int n = GLState.facade().queryMaterial(face, pname, QUERY_F);
        if (n < 0) return;
        needRemaining(params, n);
        final int p = params.position();
        for (int i = 0; i < n; i++) {
            params.put(p + i, QUERY_F[i]);
        }
    }

    public static void glGetMaterial(int face, int pname, IntBuffer params) {
        if (!renderThreadCheck()) return;
        final int n = GLState.facade().queryMaterial(face, pname, QUERY_F);
        if (n < 0) return;
        needRemaining(params, n);
        final boolean color = GLState.materialParamIsColor(pname);
        final int p = params.position();
        for (int i = 0; i < n; i++) {
            params.put(p + i, toInt(QUERY_F[i], color));
        }
    }

    public static void glGetTexEnv(int target, int pname, FloatBuffer params) {
        if (!renderThreadCheck()) return;
        final int n = GLState.facade().queryTexEnv(target, pname, QUERY_F);
        if (n < 0) return;
        needRemaining(params, n);
        final int p = params.position();
        for (int i = 0; i < n; i++) {
            params.put(p + i, QUERY_F[i]);
        }
    }

    public static void glGetTexEnv(int target, int pname, IntBuffer params) {
        if (!renderThreadCheck()) return;
        final int n = GLState.facade().queryTexEnv(target, pname, QUERY_F);
        if (n < 0) return;
        needRemaining(params, n);
        final boolean color = GLState.texEnvParamIsColor(target, pname);
        final int p = params.position();
        for (int i = 0; i < n; i++) {
            params.put(p + i, toInt(QUERY_F[i], color));
        }
    }

    public static void glGetTexGen(int coord, int pname, DoubleBuffer params) {
        if (!renderThreadCheck()) return;
        final int n = GLState.facade().queryTexGen(coord, pname, QUERY_F);
        if (n < 0) return;
        needRemaining(params, n);
        final int p = params.position();
        for (int i = 0; i < n; i++) {
            params.put(p + i, QUERY_F[i]);
        }
    }

    public static void glGetTexGen(int coord, int pname, FloatBuffer params) {
        if (!renderThreadCheck()) return;
        final int n = GLState.facade().queryTexGen(coord, pname, QUERY_F);
        if (n < 0) return;
        needRemaining(params, n);
        final int p = params.position();
        for (int i = 0; i < n; i++) {
            params.put(p + i, QUERY_F[i]);
        }
    }

    public static void glGetTexGen(int coord, int pname, IntBuffer params) {
        if (!renderThreadCheck()) return;
        final int n = GLState.facade().queryTexGen(coord, pname, QUERY_F);
        if (n < 0) return;
        needRemaining(params, n);
        final int p = params.position();
        for (int i = 0; i < n; i++) {
            params.put(p + i, toInt(QUERY_F[i], false));
        }
    }

    public static String glGetString(int name) {
        if (!renderThreadCheck()) return null;
        switch (name) {
            case GL11.GL_VENDOR:
                return GLCapabilities.vendor();
            case GL11.GL_RENDERER:
                return GLCapabilities.renderer();
            case GL11.GL_VERSION:
                return GLCapabilities.version();
            case GL11.GL_EXTENSIONS:
                return GLCapabilities.extensions();
            default:
                GLErrors.set(GL11.GL_INVALID_ENUM);
                return null;
        }
    }
}
