package net.xiaoyu233.fml.vulkan.gl.state;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StreamSlice;
import net.xiaoyu233.fml.vulkan.gl.GLCapabilities;
import net.xiaoyu233.fml.vulkan.gl.GLErrors;
import net.xiaoyu233.fml.vulkan.gl.draw.DrawSystem;
import net.xiaoyu233.fml.vulkan.pipeline.CommandRecorder;
import net.xiaoyu233.fml.vulkan.pipeline.PipelineKey;
import net.xiaoyu233.fml.vulkan.pipeline.Pipelines;
import net.xiaoyu233.fml.vulkan.pipeline.ShaderInterface;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static net.xiaoyu233.fml.vulkan.gl.state.GLConst.*;

/**
 * The OpenGL 1.5 fixed-function state machine (module D1). The state lives here as plain GL values (enums, flags, matrices);
 * everything the Vulkan side needs is derived lazily from it:
 * <ul>
 *   <li>{@link #pipelineBits()}: the PipelineKey state bits (blend, depth, cull, polygon mode/offset, shading, alpha test),</li>
 *   <li>{@link #bindUniforms()}: the 512 byte std140 uniform block (projection, texture matrices, fog, lights, material,
 *       tex env, texgen, flags) written into a fresh per-frame stream block whenever it changed,</li>
 *   <li>{@link #writePushConstants(long, int)}: modelview + current color/normal/texcoords,</li>
 *   <li>{@link #applyDynamicState()}: viewport, scissor, depth bias, line width and blend constants, only when changed.</li>
 * </ul>
 * Render thread only. The public setters (everything below the contract section) are the entry points of
 * {@code net.xiaoyu233.fml.vulkan.gl.GLStateImpl}, which owns the LWJGL 2 facade semantics (display lists, buffers, errors for
 * buffer sizes); they report GL errors through {@link GLErrors}.
 */
public final class GLState {
    static final int MODELVIEW_STACK_DEPTH = 64;
    static final int PROJECTION_STACK_DEPTH = 32;
    static final int TEXTURE_STACK_DEPTH = 32;
    static final int ATTRIB_STACK_DEPTH = 16;
    public static final int MAX_TEXTURE_UNITS = 8;
    public static final int MAX_LIGHTS = 8;
    static final int MAX_CLIP_PLANES = 6;
    /** Upper bound of a Vulkan viewport dimension we ever hand out. */
    private static final int MAX_VIEWPORT_DIMENSION = 16384;

    // capability bits (index into GLState.caps)
    static final int CAP_BLEND = 0;
    static final int CAP_DEPTH_TEST = 1;
    static final int CAP_CULL_FACE = 2;
    static final int CAP_SCISSOR_TEST = 3;
    static final int CAP_ALPHA_TEST = 4;
    static final int CAP_LIGHTING = 5;
    static final int CAP_COLOR_MATERIAL = 6;
    static final int CAP_NORMALIZE = 7;
    static final int CAP_RESCALE_NORMAL = 8;
    static final int CAP_FOG = 9;
    static final int CAP_LIGHT0 = 10; // .. 17
    static final int CAP_CLIP0 = 18; // .. 23
    static final int CAP_POLYGON_OFFSET_FILL = 24;
    static final int CAP_POLYGON_OFFSET_LINE = 25;
    static final int CAP_POLYGON_OFFSET_POINT = 26;
    static final int CAP_COLOR_LOGIC_OP = 27;
    static final int CAP_INDEX_LOGIC_OP = 28;
    static final int CAP_STENCIL_TEST = 29;
    static final int CAP_DITHER = 30;
    static final int CAP_MULTISAMPLE = 31;
    static final int CAP_SAMPLE_ALPHA_TO_COVERAGE = 32;
    static final int CAP_SAMPLE_ALPHA_TO_ONE = 33;
    static final int CAP_SAMPLE_COVERAGE = 34;
    static final int CAP_POINT_SMOOTH = 35;
    static final int CAP_LINE_SMOOTH = 36;
    static final int CAP_LINE_STIPPLE = 37;
    static final int CAP_POLYGON_SMOOTH = 38;
    static final int CAP_POLYGON_STIPPLE = 39;
    static final int CAP_AUTO_NORMAL = 40;

    private static long bit(int cap) {
        return 1L << cap;
    }

    static final long LIGHT_BITS = 0xFFL << CAP_LIGHT0;
    static final long CLIP_BITS = 0x3FL << CAP_CLIP0;
    // capability bits saved by the individual glPushAttrib groups
    static final long CAPS_COLOR_BUFFER = bit(CAP_BLEND) | bit(CAP_ALPHA_TEST) | bit(CAP_COLOR_LOGIC_OP) | bit(CAP_INDEX_LOGIC_OP)
            | bit(CAP_DITHER);
    static final long CAPS_DEPTH = bit(CAP_DEPTH_TEST);
    static final long CAPS_STENCIL = bit(CAP_STENCIL_TEST);
    static final long CAPS_POLYGON = bit(CAP_CULL_FACE) | bit(CAP_POLYGON_OFFSET_FILL) | bit(CAP_POLYGON_OFFSET_LINE)
            | bit(CAP_POLYGON_OFFSET_POINT) | bit(CAP_POLYGON_SMOOTH) | bit(CAP_POLYGON_STIPPLE);
    static final long CAPS_LIGHTING = bit(CAP_LIGHTING) | bit(CAP_COLOR_MATERIAL) | LIGHT_BITS;
    static final long CAPS_FOG = bit(CAP_FOG);
    static final long CAPS_LINE = bit(CAP_LINE_SMOOTH) | bit(CAP_LINE_STIPPLE);
    static final long CAPS_POINT = bit(CAP_POINT_SMOOTH);
    static final long CAPS_SCISSOR = bit(CAP_SCISSOR_TEST);
    static final long CAPS_TRANSFORM = bit(CAP_NORMALIZE) | bit(CAP_RESCALE_NORMAL) | CLIP_BITS;
    static final long CAPS_ALL = (1L << (CAP_AUTO_NORMAL + 1)) - 1;

    // dynamic state groups
    private static final int DYN_VIEWPORT = 1;
    private static final int DYN_SCISSOR = 2;
    private static final int DYN_DEPTH_BIAS = 4;
    private static final int DYN_LINE_WIDTH = 8;
    private static final int DYN_BLEND_CONSTANTS = 16;

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static volatile Thread renderThread;
    private static volatile GLState instance;

    // ---------------------------------------------------------------------------------------------------------------
    // state (package-private: StateQuery and AttribStack read/write it directly)
    // ---------------------------------------------------------------------------------------------------------------

    final MatrixStack modelView = new MatrixStack(MODELVIEW_STACK_DEPTH);
    final MatrixStack projection = new MatrixStack(PROJECTION_STACK_DEPTH);
    final MatrixStack[] texMatrix = new MatrixStack[MAX_TEXTURE_UNITS];
    int matrixMode;
    int activeTextureUnit;

    final float[] currentColor = new float[4];
    final float[] currentNormal = new float[3];
    final float[][] currentTexCoord = new float[MAX_TEXTURE_UNITS][4];

    long caps;
    final TexUnitState[] texUnits = new TexUnitState[MAX_TEXTURE_UNITS];

    // color buffer
    int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha, blendEqRgb, blendEqAlpha;
    final float[] blendColor = new float[4];
    int logicOp;
    /** Bit 0 R, 1 G, 2 B, 3 A (the VkColorComponentFlags layout). */
    int colorMask;
    final float[] clearColor = new float[4];
    int drawBuffer, readBuffer;
    int alphaFunc;
    float alphaRef;

    // depth / stencil
    int depthFunc;
    boolean depthMask;
    float clearDepth;
    double depthNear, depthFar;
    int stencilFunc, stencilRef, stencilValueMask, stencilWriteMask, stencilFail, stencilZFail, stencilZPass, clearStencil;

    // rasterization
    int cullFaceMode, frontFace, polyModeFront, polyModeBack, shadeModel;
    float polyOffsetFactor, polyOffsetUnits, lineWidth, pointSize;

    final int[] viewport = new int[4];
    final int[] scissorBox = new int[4];

    // lighting
    final LightState[] lights = new LightState[MAX_LIGHTS];
    final float[] lightModelAmbient = new float[4];
    boolean lightModelLocalViewer, lightModelTwoSide;
    int lightModelColorControl;
    final MaterialState material = new MaterialState();
    final MaterialState materialBack = new MaterialState();
    int colorMaterialFace, colorMaterialMode;

    final FogState fog = new FogState();
    final int[] hints = new int[7];

    final AttribStack attribStack = new AttribStack();

    // derived state
    private long pipelineBits;
    private boolean pipelineDirty = true;
    private boolean uniformsDirty = true;

    // uniform block of the current frame
    private StreamSlice uniformSlice;
    private long uniformSet;
    private int uniformOffset;
    private long uniformFrame = -1;
    private long uniformStamp;

    // dynamic state last handed to the CommandRecorder
    private boolean dynValid;
    private int pVpX, pVpY, pVpW, pVpH, pFbH;
    private int pScX, pScY, pScW, pScH;
    private float pBiasUnits, pBiasFactor, pLineWidth;
    private final float[] pBlendConstants = new float[4];
    private int lastFbW, lastFbH;
    private boolean flushing;

    // scratch results of computeScissor()
    private int scX, scY, scW, scH;

    private GLState() {
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            texMatrix[i] = new MatrixStack(TEXTURE_STACK_DEPTH);
            texUnits[i] = new TexUnitState();
        }
        for (int i = 0; i < MAX_LIGHTS; i++) {
            lights[i] = new LightState();
        }
        reset();
    }

    /** Puts every piece of state back to the GL initial values (a fresh context). */
    private void reset() {
        modelView.reset();
        projection.reset();
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            texMatrix[i].reset();
            texUnits[i].reset();
            LightState.set4(currentTexCoord[i], 0f, 0f, 0f, 1f);
        }
        matrixMode = GL_MODELVIEW;
        activeTextureUnit = 0;
        LightState.set4(currentColor, 1f, 1f, 1f, 1f);
        currentNormal[0] = 0f;
        currentNormal[1] = 0f;
        currentNormal[2] = 1f;

        caps = bit(CAP_DITHER) | bit(CAP_MULTISAMPLE);

        blendSrcRgb = blendSrcAlpha = GL_ONE;
        blendDstRgb = blendDstAlpha = GL_ZERO;
        blendEqRgb = blendEqAlpha = GL_FUNC_ADD;
        LightState.set4(blendColor, 0f, 0f, 0f, 0f);
        logicOp = GL_COPY;
        colorMask = 0xF;
        LightState.set4(clearColor, 0f, 0f, 0f, 0f);
        drawBuffer = GL_BACK;
        readBuffer = GL_BACK;
        alphaFunc = GL_ALWAYS;
        alphaRef = 0f;

        depthFunc = GL_LESS;
        depthMask = true;
        clearDepth = 1f;
        depthNear = 0.0;
        depthFar = 1.0;
        stencilFunc = GL_ALWAYS;
        stencilRef = 0;
        stencilValueMask = -1;
        stencilWriteMask = -1;
        stencilFail = stencilZFail = stencilZPass = GL_KEEP;
        clearStencil = 0;

        cullFaceMode = GL_BACK;
        frontFace = GL_CCW;
        polyModeFront = polyModeBack = GL_FILL;
        shadeModel = GL_SMOOTH;
        polyOffsetFactor = 0f;
        polyOffsetUnits = 0f;
        lineWidth = 1f;
        pointSize = 1f;

        int w = 0, h = 0;
        if (RenderCore.isInitialized()) {
            w = RenderCore.framebufferWidth();
            h = RenderCore.framebufferHeight();
        }
        lastFbW = w;
        lastFbH = h;
        viewport[0] = 0;
        viewport[1] = 0;
        viewport[2] = w;
        viewport[3] = h;
        scissorBox[0] = 0;
        scissorBox[1] = 0;
        scissorBox[2] = w;
        scissorBox[3] = h;

        for (int i = 0; i < MAX_LIGHTS; i++) {
            lights[i].reset(i);
        }
        LightState.set4(lightModelAmbient, 0.2f, 0.2f, 0.2f, 1f);
        lightModelLocalViewer = false;
        lightModelTwoSide = false;
        lightModelColorControl = GL_SINGLE_COLOR;
        material.reset();
        materialBack.reset();
        colorMaterialFace = GL_FRONT_AND_BACK;
        colorMaterialMode = GL_AMBIENT_AND_DIFFUSE;
        fog.reset();
        for (int i = 0; i < hints.length; i++) {
            hints[i] = GL_DONT_CARE;
        }

        attribStack.reset();
        pipelineDirty = true;
        uniformsDirty = true;
        uniformSlice = null;
        uniformFrame = -1;
        dynValid = false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Called by VulkanBackend.start after RenderCore/Pipelines/TextureManager are up: (re)initializes the state to the GL defaults,
     * records the render thread and hooks the frame listener. The instance identity stays stable across re-initialization.
     */
    public static void init() {
        final GLState state = get();
        state.reset();
        renderThread = Thread.currentThread();
        GLCapabilities.reset();
        RenderCore.addListener(new RenderCore.FrameListener() {
            @Override
            public void onFrameBegin() {
                state.uniformSlice = null;
                state.uniformsDirty = true;
            }

            @Override
            public void onCommandBufferReset() {
                // CommandRecorder dropped its cached binds and dynamic state: push everything again before the next draw
                state.dynValid = false;
            }

            @Override
            public void onResize(int w, int h) {
                state.onResize(w, h);
            }
        });
    }

    public static GLState get() {
        GLState s = instance;
        if (s == null) {
            synchronized (GLState.class) {
                s = instance;
                if (s == null) {
                    s = new GLState();
                    instance = s;
                }
            }
        }
        return s;
    }

    /**
     * Facade guard: true on the render thread (or while no render thread is known yet). A call from any other thread is logged
     * once and must be ignored by the caller.
     */
    public static boolean checkRenderThread() {
        final Thread rt = renderThread;
        if (rt == null || rt == Thread.currentThread()) {
            return true;
        }
        if (WARNED.add("offThread")) {
            VkLog.LOGGER.warn("OpenGL call from thread '{}' ignored: GL calls are only valid on the render thread '{}' "
                    + "(further off-thread calls are ignored silently)", Thread.currentThread().getName(), rt.getName());
        }
        return false;
    }

    /** Logs {@code message} (WARN) the first time {@code key} is seen. */
    public static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            VkLog.LOGGER.warn(message);
        }
    }

    static void error(int glError) {
        GLErrors.set(glError);
    }

    private void onResize(int w, int h) {
        if (w == lastFbW && h == lastFbH) {
            return;
        }
        // GL keeps the viewport/scissor when the window changes; we only follow the framebuffer while they still cover all of it
        if (viewport[0] == 0 && viewport[1] == 0 && viewport[2] == lastFbW && viewport[3] == lastFbH) {
            viewport[2] = w;
            viewport[3] = h;
        }
        if (scissorBox[0] == 0 && scissorBox[1] == 0 && scissorBox[2] == lastFbW && scissorBox[3] == lastFbH) {
            scissorBox[2] = w;
            scissorBox[3] = h;
        }
        lastFbW = w;
        lastFbH = h;
        dynValid = false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // contract API (../docs/ARCHITECTURE.md (outside the repo) section 5.9)
    // ---------------------------------------------------------------------------------------------------------------

    /** The active texture unit, 0-based (glActiveTexture). */
    public int activeTexture() {
        return activeTextureUnit;
    }

    /** glEnable(GL_TEXTURE_2D) on that unit. */
    public boolean texture2D(int unit) {
        return (texUnits[unit & (MAX_TEXTURE_UNITS - 1)].targetEnable & TexUnitState.TARGET_2D) != 0;
    }

    /** PipelineKey bits for every field except the topology (D2 ORs it in). */
    public long pipelineBits() {
        if (pipelineDirty) {
            rebuildPipelineBits();
        }
        return pipelineBits;
    }

    public boolean flatShading() {
        return shadeModel == GL_FLAT;
    }

    /** Live current color (RGBA); do not modify. */
    public float[] currentColor() {
        return currentColor;
    }

    /** Live current normal (xyz); do not modify. */
    public float[] currentNormal() {
        return currentNormal;
    }

    /** Live current texture coordinate (s, t, r, q) of the unit; do not modify. */
    public float[] currentTexCoord(int unit) {
        return currentTexCoord[unit & (MAX_TEXTURE_UNITS - 1)];
    }

    /** Live column-major 16-float top of the modelview stack; do not modify. */
    public float[] modelViewMatrix() {
        return modelView.top();
    }

    /**
     * Identity of the uniform block bound by the last {@link #bindUniforms()}: it changes exactly when a new block was written,
     * so two draws with the same stamp share the same uniform buffer offset (usable as a draw-merging key).
     */
    public long uniformStamp() {
        return uniformStamp;
    }

    /**
     * Ensures an up-to-date UBO block exists in the current frame (a new stream block is written only when the uniform state
     * changed since the last one) and binds it through the CommandRecorder.
     */
    public void bindUniforms() {
        final long frame = RenderCore.frameNumber();
        if (uniformSlice == null || uniformsDirty || uniformFrame != frame) {
            // draws that are still pending were recorded against the previous block: let them go out before we rebind
            flushPending();
            final StreamSlice slice = RenderCore.streamUniform(ShaderInterface.UBO_SIZE);
            packUniforms(slice.address());
            uniformSlice = slice;
            uniformSet = Pipelines.uniformSet(slice.buffer());
            uniformOffset = (int) slice.offset();
            uniformFrame = frame;
            uniformsDirty = false;
            uniformStamp++;
        }
        CommandRecorder.bindUniforms(uniformSet, uniformOffset);
    }

    /** Draw trace ({@code fml.vulkan.traceFrame}): the state that decides how a draw looks, in words. */
    public String describeForTrace() {
        final TexUnitState u0 = texUnits[0];
        final TexUnitState u1 = texUnits[1];
        return String.format("blend=%b(%x,%x|%x,%x) depth=%b/%b/%x cull=%b/%x front=%x alpha=%b(%x,%.2f) fog=%b(%x %.1f..%.1f d%.3f) "
                        + "light=%b tex0=%b env0=%x tex1=%b env1=%x mask=%x shade=%x color=(%.2f,%.2f,%.2f,%.2f) texgen=%x poly=%b",
                has(CAP_BLEND), blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha, has(CAP_DEPTH_TEST), depthMask, depthFunc,
                has(CAP_CULL_FACE), cullFaceMode, frontFace, has(CAP_ALPHA_TEST), alphaFunc, alphaRef, has(CAP_FOG), fog.mode, fog.start,
                fog.end, fog.density, has(CAP_LIGHTING), (u0.targetEnable & TexUnitState.TARGET_2D) != 0, u0.envMode,
                (u1.targetEnable & TexUnitState.TARGET_2D) != 0, u1.envMode, colorMask, shadeModel, currentColor[0], currentColor[1],
                currentColor[2], currentColor[3], u0.texGenEnable, has(CAP_POLYGON_OFFSET_FILL));
    }

    /** Writes the 128 byte push constant block (modelview, current color/normal/texcoords, attribMask) to native memory. */
    public void writePushConstants(long address, int attribMask) {
        final float[] mv = modelView.top();
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(address + ShaderInterface.PC_MODEL_VIEW + (i << 2), mv[i]);
        }
        putVec4(address, ShaderInterface.PC_COLOR, currentColor);
        MemoryUtil.memPutFloat(address + ShaderInterface.PC_NORMAL, currentNormal[0]);
        MemoryUtil.memPutFloat(address + ShaderInterface.PC_NORMAL + 4, currentNormal[1]);
        MemoryUtil.memPutFloat(address + ShaderInterface.PC_NORMAL + 8, currentNormal[2]);
        MemoryUtil.memPutFloat(address + ShaderInterface.PC_NORMAL + 12, 0f);
        putVec4(address, ShaderInterface.PC_TEXCOORD0, currentTexCoord[0]);
        MemoryUtil.memPutFloat(address + ShaderInterface.PC_TEXCOORD1, currentTexCoord[1][0]);
        MemoryUtil.memPutFloat(address + ShaderInterface.PC_TEXCOORD1 + 4, currentTexCoord[1][1]);
        MemoryUtil.memPutInt(address + ShaderInterface.PC_ATTRIB_MASK, attribMask);
        MemoryUtil.memPutInt(address + ShaderInterface.PC_RESERVED, 0);
    }

    /**
     * Pushes viewport, scissor, depth bias, line width and blend constants to the CommandRecorder, but only the ones that
     * changed since the last push into the current command buffer (everything again after a command buffer reset).
     */
    public void applyDynamicState() {
        final int fbW = RenderCore.framebufferWidth();
        final int fbH = RenderCore.framebufferHeight();
        int dirty = 0;

        final int vx = viewport[0], vy = viewport[1], vw = viewport[2], vh = viewport[3];
        if (!dynValid || vx != pVpX || vy != pVpY || vw != pVpW || vh != pVpH || fbH != pFbH) {
            dirty |= DYN_VIEWPORT;
        }

        final int sx, sy, sw, sh;
        if (has(CAP_SCISSOR_TEST)) {
            if (computeScissor(fbW, fbH)) {
                sx = scX;
                sy = fbH - scY - scH;
                sw = scW;
                sh = scH;
            } else {
                sx = 0;
                sy = 0;
                sw = 0;
                sh = 0;
            }
        } else {
            sx = 0;
            sy = 0;
            sw = fbW;
            sh = fbH;
        }
        if (!dynValid || sx != pScX || sy != pScY || sw != pScW || sh != pScH) {
            dirty |= DYN_SCISSOR;
        }

        // GL: offset = factor * DZ + r * units; Vulkan: depthBiasConstantFactor * r + depthBiasSlopeFactor * DZ
        if (!dynValid || polyOffsetUnits != pBiasUnits || polyOffsetFactor != pBiasFactor) {
            dirty |= DYN_DEPTH_BIAS;
        }
        if (!dynValid || lineWidth != pLineWidth) {
            dirty |= DYN_LINE_WIDTH;
        }
        final float[] bc = blendColor;
        if (!dynValid || bc[0] != pBlendConstants[0] || bc[1] != pBlendConstants[1] || bc[2] != pBlendConstants[2]
                || bc[3] != pBlendConstants[3]) {
            dirty |= DYN_BLEND_CONSTANTS;
        }
        if (dirty == 0) {
            return;
        }

        // pending merged draws were recorded with the old values: let them go out before the recorder's cache changes
        flushPending();

        if ((dirty & DYN_VIEWPORT) != 0) {
            // GL (x, y, w, h) with a bottom-left origin -> Vulkan (x, fbH - y, w, -h) (negative height flips Y)
            final int w = Math.max(1, Math.min(vw, MAX_VIEWPORT_DIMENSION));
            final int h = Math.max(1, Math.min(vh, MAX_VIEWPORT_DIMENSION));
            CommandRecorder.setViewport(vx, fbH - vy, w, -h);
            pVpX = vx;
            pVpY = vy;
            pVpW = vw;
            pVpH = vh;
            pFbH = fbH;
        }
        if ((dirty & DYN_SCISSOR) != 0) {
            CommandRecorder.setScissor(sx, sy, sw, sh);
            pScX = sx;
            pScY = sy;
            pScW = sw;
            pScH = sh;
        }
        if ((dirty & DYN_DEPTH_BIAS) != 0) {
            CommandRecorder.setDepthBias(polyOffsetUnits, polyOffsetFactor);
            pBiasUnits = polyOffsetUnits;
            pBiasFactor = polyOffsetFactor;
        }
        if ((dirty & DYN_LINE_WIDTH) != 0) {
            CommandRecorder.setLineWidth(lineWidth);
            pLineWidth = lineWidth;
        }
        if ((dirty & DYN_BLEND_CONSTANTS) != 0) {
            CommandRecorder.setBlendConstants(bc[0], bc[1], bc[2], bc[3]);
            System.arraycopy(bc, 0, pBlendConstants, 0, 4);
        }
        dynValid = true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------------

    boolean has(int cap) {
        return (caps & (1L << cap)) != 0;
    }

    /** Lets draws that D2 still holds back go out; guarded so a flush that re-enters this class cannot recurse. */
    private void flushPending() {
        if (flushing) {
            return;
        }
        flushing = true;
        try {
            DrawSystem.flush();
        } finally {
            flushing = false;
        }
    }

    private static DeviceInfo deviceInfo() {
        return RenderCore.isInitialized() ? RenderCore.deviceInfo() : null;
    }

    private static void putVec4(long address, int offset, float[] v) {
        MemoryUtil.memPutFloat(address + offset, v[0]);
        MemoryUtil.memPutFloat(address + offset + 4, v[1]);
        MemoryUtil.memPutFloat(address + offset + 8, v[2]);
        MemoryUtil.memPutFloat(address + offset + 12, v[3]);
    }

    private static void putMat4(long address, int offset, float[] m) {
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(address + offset + (i << 2), m[i]);
        }
    }

    /** Copies {@code src[0..n)} into {@code dst}; true when a value changed. */
    private static boolean assign(float[] dst, float[] src, int n) {
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            if (dst[i] != src[i]) {
                dst[i] = src[i];
                changed = true;
            }
        }
        return changed;
    }

    /**
     * The GL scissor rectangle clipped to the framebuffer, in GL window coordinates (bottom-left origin) into scX/scY/scW/scH.
     * False when the clipped rectangle is empty.
     */
    private boolean computeScissor(int fbW, int fbH) {
        final long x0 = Math.max(0L, scissorBox[0]);
        final long y0 = Math.max(0L, scissorBox[1]);
        final long x1 = Math.min((long) fbW, (long) scissorBox[0] + scissorBox[2]);
        final long y1 = Math.min((long) fbH, (long) scissorBox[1] + scissorBox[3]);
        if (x1 <= x0 || y1 <= y0) {
            return false;
        }
        scX = (int) x0;
        scY = (int) y0;
        scW = (int) (x1 - x0);
        scH = (int) (y1 - y0);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // pipeline key
    // ---------------------------------------------------------------------------------------------------------------

    private void rebuildPipelineBits() {
        long k = PipelineKey.DEFAULT;

        boolean blend = has(CAP_BLEND);
        int sc = blendFactorToVk(blendSrcRgb), dc = blendFactorToVk(blendDstRgb);
        int sa = blendFactorToVk(blendSrcAlpha), da = blendFactorToVk(blendDstAlpha);
        int oc = blendOpToVk(blendEqRgb), oa = blendOpToVk(blendEqAlpha);
        if (has(CAP_COLOR_LOGIC_OP)) {
            // Logic ops are emulated with blending (ARCHITECTURE 6.11); an enabled logic op replaces the user's blend state.
            // CLEAR, NOOP and XOR are exact for the per-channel 0/1 colors fixed-function code uses; OR_REVERSE (MITE's text
            // selection highlight), INVERT and EQUIV share the contract's "src * (1 - dst)" approximation; every other op is
            // not emulated: it is logged once and drawn like COPY with the user's blend state.
            switch (logicOp) {
                case GL_COPY:
                    break;
                case GL_CLEAR:
                    blend = true;
                    sc = dc = sa = da = VK10.VK_BLEND_FACTOR_ZERO;
                    oc = oa = VK10.VK_BLEND_OP_ADD;
                    break;
                case GL_NOOP:
                    blend = true;
                    sc = sa = VK10.VK_BLEND_FACTOR_ZERO;
                    dc = da = VK10.VK_BLEND_FACTOR_ONE;
                    oc = oa = VK10.VK_BLEND_OP_ADD;
                    break;
                case GL_OR_REVERSE: // the one MITE uses (GuiTextField selection highlight): no warning
                case GL_INVERT:
                case GL_EQUIV:
                    if (logicOp != GL_OR_REVERSE) {
                        warnLogicOp(logicOp, "is only approximated with blending (source * (1 - destination))");
                    }
                    blend = true;
                    sc = VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
                    sa = VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
                    dc = da = VK10.VK_BLEND_FACTOR_ZERO;
                    oc = oa = VK10.VK_BLEND_OP_ADD;
                    break;
                case GL_XOR:
                    // exact for 0/1 channels: s*(1-d) + d*(1-s) = s xor d (the plain "src * (1 - dst)" would black out s = 0)
                    blend = true;
                    sc = VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
                    dc = VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
                    sa = VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
                    da = VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                    oc = oa = VK10.VK_BLEND_OP_ADD;
                    break;
                default:
                    warnLogicOp(logicOp, "is not emulated; drawing with the normal blend state instead");
                    break;
            }
        }
        k = PipelineKey.set(k, PipelineKey.BLEND_ENABLE, blend ? 1 : 0);
        k = PipelineKey.set(k, PipelineKey.SRC_COLOR_BLEND_FACTOR, sc);
        k = PipelineKey.set(k, PipelineKey.DST_COLOR_BLEND_FACTOR, dc);
        k = PipelineKey.set(k, PipelineKey.SRC_ALPHA_BLEND_FACTOR, sa);
        k = PipelineKey.set(k, PipelineKey.DST_ALPHA_BLEND_FACTOR, da);
        k = PipelineKey.set(k, PipelineKey.COLOR_BLEND_OP, oc);
        k = PipelineKey.set(k, PipelineKey.ALPHA_BLEND_OP, oa);

        k = PipelineKey.set(k, PipelineKey.DEPTH_TEST_ENABLE, has(CAP_DEPTH_TEST) ? 1 : 0);
        k = PipelineKey.set(k, PipelineKey.DEPTH_WRITE_ENABLE, depthMask ? 1 : 0);
        k = PipelineKey.set(k, PipelineKey.DEPTH_COMPARE_OP, compareFuncToVk(depthFunc));

        k = PipelineKey.set(k, PipelineKey.CULL_MODE, has(CAP_CULL_FACE) ? cullModeToVk(cullFaceMode) : VK10.VK_CULL_MODE_NONE);
        // with the negative-height viewport GL CCW is VK_FRONT_FACE_COUNTER_CLOCKWISE
        k = PipelineKey.set(k, PipelineKey.FRONT_FACE_CLOCKWISE, frontFace == GL_CW ? 1 : 0);
        k = PipelineKey.set(k, PipelineKey.COLOR_WRITE_MASK, drawBuffer == GL_NONE ? 0 : colorMask);

        final int glPolygonMode = polyModeFront;
        k = PipelineKey.set(k, PipelineKey.POLYGON_MODE, polygonModeToVk(glPolygonMode));
        final int offsetCap = glPolygonMode == GL_POINT ? CAP_POLYGON_OFFSET_POINT
                : glPolygonMode == GL_LINE ? CAP_POLYGON_OFFSET_LINE : CAP_POLYGON_OFFSET_FILL;
        k = PipelineKey.set(k, PipelineKey.DEPTH_BIAS_ENABLE,
                has(offsetCap) && (polyOffsetFactor != 0f || polyOffsetUnits != 0f) ? 1 : 0);

        k = PipelineKey.set(k, PipelineKey.FLAT_SHADING, shadeModel == GL_FLAT ? 1 : 0);
        k = PipelineKey.set(k, PipelineKey.ALPHA_TEST, has(CAP_ALPHA_TEST) && alphaFunc != GL_ALWAYS ? 1 : 0);

        pipelineBits = k;
        pipelineDirty = false;
    }

    /** Bit (op - GL_CLEAR) set once the emulation limits of that logic op were logged. */
    private static int loggedLogicOps;

    /** Logs once per logic op; the bit test comes first so repeated rebuilds never build the message (no allocation). */
    private static void warnLogicOp(int op, String how) {
        final int bit = 1 << (op - GL_CLEAR);
        if ((loggedLogicOps & bit) == 0) {
            loggedLogicOps |= bit;
            warnOnce("logicOp." + op, "glLogicOp(0x" + Integer.toHexString(op) + ") " + how);
        }
    }

    private int polygonModeToVk(int glMode) {
        if (polyModeFront != polyModeBack) {
            warnOnce("polygonMode.mixed", "glPolygonMode with different front/back modes is not supported by Vulkan; "
                    + "the front face mode is used for both");
        }
        if (glMode == GL_FILL) {
            return VK10.VK_POLYGON_MODE_FILL;
        }
        final DeviceInfo di = deviceInfo();
        if (di == null || !di.fillModeNonSolid()) {
            warnOnce("polygonMode.fillModeNonSolid", "glPolygonMode(LINE/POINT) needs the fillModeNonSolid device feature; "
                    + "polygons stay filled");
            return VK10.VK_POLYGON_MODE_FILL;
        }
        if (glMode == GL_POINT) {
            if (di.isMoltenVK()) {
                warnOnce("polygonMode.point", "glPolygonMode(GL_POINT) is not supported on MoltenVK (portability subset): "
                        + "drawing polygons as lines instead");
                return VK10.VK_POLYGON_MODE_LINE;
            }
            return VK10.VK_POLYGON_MODE_POINT;
        }
        return VK10.VK_POLYGON_MODE_LINE;
    }

    private static int blendFactorToVk(int gl) {
        switch (gl) {
            case GL_ZERO:
                return VK10.VK_BLEND_FACTOR_ZERO;
            case GL_ONE:
                return VK10.VK_BLEND_FACTOR_ONE;
            case GL_SRC_COLOR:
                return VK10.VK_BLEND_FACTOR_SRC_COLOR;
            case GL_ONE_MINUS_SRC_COLOR:
                return VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case GL_DST_COLOR:
                return VK10.VK_BLEND_FACTOR_DST_COLOR;
            case GL_ONE_MINUS_DST_COLOR:
                return VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case GL_SRC_ALPHA:
                return VK10.VK_BLEND_FACTOR_SRC_ALPHA;
            case GL_ONE_MINUS_SRC_ALPHA:
                return VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case GL_DST_ALPHA:
                return VK10.VK_BLEND_FACTOR_DST_ALPHA;
            case GL_ONE_MINUS_DST_ALPHA:
                return VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case GL_SRC_ALPHA_SATURATE:
                return VK10.VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case GL_CONSTANT_COLOR:
                return VK10.VK_BLEND_FACTOR_CONSTANT_COLOR;
            case GL_ONE_MINUS_CONSTANT_COLOR:
                return VK10.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case GL_CONSTANT_ALPHA:
                return VK10.VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case GL_ONE_MINUS_CONSTANT_ALPHA:
                return VK10.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            default:
                return VK10.VK_BLEND_FACTOR_ONE;
        }
    }

    private static boolean validBlendFactor(int gl, boolean source) {
        switch (gl) {
            case GL_ZERO:
            case GL_ONE:
            case GL_SRC_COLOR:
            case GL_ONE_MINUS_SRC_COLOR:
            case GL_DST_COLOR:
            case GL_ONE_MINUS_DST_COLOR:
            case GL_SRC_ALPHA:
            case GL_ONE_MINUS_SRC_ALPHA:
            case GL_DST_ALPHA:
            case GL_ONE_MINUS_DST_ALPHA:
            case GL_CONSTANT_COLOR:
            case GL_ONE_MINUS_CONSTANT_COLOR:
            case GL_CONSTANT_ALPHA:
            case GL_ONE_MINUS_CONSTANT_ALPHA:
                return true;
            case GL_SRC_ALPHA_SATURATE:
                return source;
            default:
                return false;
        }
    }

    private static int blendOpToVk(int gl) {
        switch (gl) {
            case GL_FUNC_SUBTRACT:
                return VK10.VK_BLEND_OP_SUBTRACT;
            case GL_FUNC_REVERSE_SUBTRACT:
                return VK10.VK_BLEND_OP_REVERSE_SUBTRACT;
            case GL_MIN:
                return VK10.VK_BLEND_OP_MIN;
            case GL_MAX:
                return VK10.VK_BLEND_OP_MAX;
            default:
                return VK10.VK_BLEND_OP_ADD;
        }
    }

    private static boolean validBlendEquation(int gl) {
        return gl == GL_FUNC_ADD || gl == GL_FUNC_SUBTRACT || gl == GL_FUNC_REVERSE_SUBTRACT || gl == GL_MIN || gl == GL_MAX;
    }

    private static boolean validCompareFunc(int gl) {
        return gl >= GL_NEVER && gl <= GL_ALWAYS;
    }

    /** GL_NEVER..GL_ALWAYS (0x200..0x207) are in the same order as VK_COMPARE_OP_NEVER..ALWAYS. */
    private static int compareFuncToVk(int gl) {
        return validCompareFunc(gl) ? gl - GL_NEVER : VK10.VK_COMPARE_OP_LESS;
    }

    private static int cullModeToVk(int gl) {
        switch (gl) {
            case GL_FRONT:
                return VK10.VK_CULL_MODE_FRONT_BIT;
            case GL_FRONT_AND_BACK:
                return VK10.VK_CULL_MODE_FRONT_AND_BACK;
            case GL_BACK:
            default:
                return VK10.VK_CULL_MODE_BACK_BIT;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // uniform block packing (ShaderInterface section 5.3, byte exact)
    // ---------------------------------------------------------------------------------------------------------------

    private void packUniforms(long a) {
        putMat4(a, ShaderInterface.UBO_PROJECTION, projection.top());
        putMat4(a, ShaderInterface.UBO_TEX_MATRIX0, texMatrix[0].top());
        putMat4(a, ShaderInterface.UBO_TEX_MATRIX1, texMatrix[1].top());

        putVec4(a, ShaderInterface.UBO_FOG_COLOR, fog.color);
        MemoryUtil.memPutFloat(a + ShaderInterface.UBO_FOG_PARAMS, fog.start);
        MemoryUtil.memPutFloat(a + ShaderInterface.UBO_FOG_PARAMS + 4, fog.end);
        MemoryUtil.memPutFloat(a + ShaderInterface.UBO_FOG_PARAMS + 8, fog.density);
        MemoryUtil.memPutFloat(a + ShaderInterface.UBO_FOG_PARAMS + 12, fog.end == fog.start ? 0f : 1f / (fog.end - fog.start));

        putVec4(a, ShaderInterface.UBO_LIGHT_MODEL_AMBIENT, lightModelAmbient);
        packLight(a, ShaderInterface.UBO_LIGHT0_POSITION, ShaderInterface.UBO_LIGHT0_DIFFUSE, ShaderInterface.UBO_LIGHT0_AMBIENT,
                lights[0]);
        packLight(a, ShaderInterface.UBO_LIGHT1_POSITION, ShaderInterface.UBO_LIGHT1_DIFFUSE, ShaderInterface.UBO_LIGHT1_AMBIENT,
                lights[1]);

        putVec4(a, ShaderInterface.UBO_MATERIAL_AMBIENT, material.ambient);
        putVec4(a, ShaderInterface.UBO_MATERIAL_DIFFUSE, material.diffuse);
        putVec4(a, ShaderInterface.UBO_MATERIAL_EMISSION, material.emission);

        final TexUnitState u0 = texUnits[0];
        final TexUnitState u1 = texUnits[1];
        putVec4(a, ShaderInterface.UBO_TEX_ENV_COLOR0, u0.envColor);

        // texgen planes of unit 0: the plane of the coordinate's current mode (EYE planes are stored pre-multiplied)
        int texGenModes = 0;
        for (int c = 0; c < 4; c++) {
            final int mode = u0.genMode[c];
            final int shaderMode;
            final float[] plane;
            if (mode == GL_EYE_LINEAR) {
                shaderMode = ShaderInterface.TEXGEN_EYE_LINEAR;
                plane = u0.eyePlane[c];
            } else if (mode == GL_SPHERE_MAP) {
                shaderMode = ShaderInterface.TEXGEN_SPHERE_MAP;
                plane = ZERO4;
            } else {
                // GL_OBJECT_LINEAR (and the unsupported cube map modes, which degrade to object linear)
                shaderMode = ShaderInterface.TEXGEN_OBJECT_LINEAR;
                plane = u0.objectPlane[c];
            }
            putVec4(a, ShaderInterface.UBO_TEXGEN_PLANE_S + (c << 4), plane);
            texGenModes |= (shaderMode & ShaderInterface.TEXGEN_MODE_MASK) << (c << 1);
        }

        final int fogMode = !has(CAP_FOG) ? ShaderInterface.FOG_OFF
                : fog.mode == GL_EXP2 ? ShaderInterface.FOG_EXP2
                : fog.mode == GL_EXP ? ShaderInterface.FOG_EXP : ShaderInterface.FOG_LINEAR;
        MemoryUtil.memPutInt(a + ShaderInterface.UBO_MODES_FOG_MODE, fogMode);
        MemoryUtil.memPutInt(a + ShaderInterface.UBO_MODES_ALPHA_FUNC, alphaFunc - GL_NEVER);
        MemoryUtil.memPutInt(a + ShaderInterface.UBO_MODES_TEX_ENV0, texEnvToShader(u0.envMode));
        MemoryUtil.memPutInt(a + ShaderInterface.UBO_MODES_TEX_ENV1, texEnvToShader(u1.envMode));

        int flags = 0;
        if ((u0.targetEnable & TexUnitState.TARGET_2D) != 0) flags |= ShaderInterface.FLAG_TEX0_ENABLED;
        if ((u1.targetEnable & TexUnitState.TARGET_2D) != 0) flags |= ShaderInterface.FLAG_TEX1_ENABLED;
        if (has(CAP_LIGHTING)) flags |= ShaderInterface.FLAG_LIGHTING;
        if (has(CAP_LIGHT0)) flags |= ShaderInterface.FLAG_LIGHT0;
        if (has(CAP_LIGHT0 + 1)) flags |= ShaderInterface.FLAG_LIGHT1;
        if (colorMaterialActive()) flags |= ShaderInterface.FLAG_COLOR_MATERIAL;
        if (has(CAP_NORMALIZE)) flags |= ShaderInterface.FLAG_NORMALIZE;
        if (has(CAP_RESCALE_NORMAL)) flags |= ShaderInterface.FLAG_RESCALE_NORMAL;
        if (fog.distanceMode == GL_EYE_RADIAL_NV) flags |= ShaderInterface.FLAG_FOG_RADIAL;
        if ((u0.texGenEnable & 1) != 0) flags |= ShaderInterface.FLAG_TEXGEN_S;
        if ((u0.texGenEnable & 2) != 0) flags |= ShaderInterface.FLAG_TEXGEN_T;
        if ((u0.texGenEnable & 4) != 0) flags |= ShaderInterface.FLAG_TEXGEN_R;
        if ((u0.texGenEnable & 8) != 0) flags |= ShaderInterface.FLAG_TEXGEN_Q;
        MemoryUtil.memPutInt(a + ShaderInterface.UBO_FLAGS_BITS, flags);
        MemoryUtil.memPutInt(a + ShaderInterface.UBO_FLAGS_TEXGEN_MODES, texGenModes);
        MemoryUtil.memPutInt(a + ShaderInterface.UBO_FLAGS_COLOR_MATERIAL_MODE, colorMaterialToShader(colorMaterialMode));
        MemoryUtil.memPutInt(a + ShaderInterface.UBO_FLAGS_W, 0);

        MemoryUtil.memPutFloat(a + ShaderInterface.UBO_ALPHA_REF, alphaRef);
        MemoryUtil.memPutFloat(a + ShaderInterface.UBO_ALPHA_REF + 4, 0f);
        MemoryUtil.memPutFloat(a + ShaderInterface.UBO_ALPHA_REF + 8, 0f);
        MemoryUtil.memPutFloat(a + ShaderInterface.UBO_ALPHA_REF + 12, 0f);
    }

    private static final float[] ZERO4 = new float[4];

    private static void packLight(long a, int positionOffset, int diffuseOffset, int ambientOffset, LightState l) {
        putVec4(a, positionOffset, l.position);
        putVec4(a, diffuseOffset, l.diffuse);
        putVec4(a, ambientOffset, l.ambient);
    }

    /** The shader substitutes the vertex color into the front material only; a GL_BACK-only color material is a no-op. */
    private boolean colorMaterialActive() {
        return has(CAP_COLOR_MATERIAL) && colorMaterialFace != GL_BACK;
    }

    private static int texEnvToShader(int gl) {
        switch (gl) {
            case GL_REPLACE:
                return ShaderInterface.TEXENV_REPLACE;
            case GL_DECAL:
                return ShaderInterface.TEXENV_DECAL;
            case GL_BLEND:
                return ShaderInterface.TEXENV_BLEND;
            case GL_ADD:
                return ShaderInterface.TEXENV_ADD;
            case GL_COMBINE:
                return ShaderInterface.TEXENV_COMBINE;
            case GL_MODULATE:
            default:
                return ShaderInterface.TEXENV_MODULATE;
        }
    }

    private static int colorMaterialToShader(int gl) {
        switch (gl) {
            case GL_AMBIENT:
                return ShaderInterface.COLOR_MATERIAL_AMBIENT;
            case GL_DIFFUSE:
                return ShaderInterface.COLOR_MATERIAL_DIFFUSE;
            case GL_EMISSION:
                return ShaderInterface.COLOR_MATERIAL_EMISSION;
            case GL_SPECULAR:
                return ShaderInterface.COLOR_MATERIAL_SPECULAR;
            case GL_AMBIENT_AND_DIFFUSE:
            default:
                return ShaderInterface.COLOR_MATERIAL_AMBIENT_AND_DIFFUSE;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // capabilities (glEnable / glDisable / glIsEnabled)
    // ---------------------------------------------------------------------------------------------------------------

    /** The capability bit of a glEnable cap, or -1 when the cap is not a plain on/off flag (or unknown). */
    static int capBit(int cap) {
        switch (cap) {
            case GL_BLEND:
                return CAP_BLEND;
            case GL_DEPTH_TEST:
                return CAP_DEPTH_TEST;
            case GL_CULL_FACE:
                return CAP_CULL_FACE;
            case GL_SCISSOR_TEST:
                return CAP_SCISSOR_TEST;
            case GL_ALPHA_TEST:
                return CAP_ALPHA_TEST;
            case GL_LIGHTING:
                return CAP_LIGHTING;
            case GL_COLOR_MATERIAL:
                return CAP_COLOR_MATERIAL;
            case GL_NORMALIZE:
                return CAP_NORMALIZE;
            case GL_RESCALE_NORMAL:
                return CAP_RESCALE_NORMAL;
            case GL_FOG:
                return CAP_FOG;
            case GL_POLYGON_OFFSET_FILL:
                return CAP_POLYGON_OFFSET_FILL;
            case GL_POLYGON_OFFSET_LINE:
                return CAP_POLYGON_OFFSET_LINE;
            case GL_POLYGON_OFFSET_POINT:
                return CAP_POLYGON_OFFSET_POINT;
            case GL_COLOR_LOGIC_OP:
                return CAP_COLOR_LOGIC_OP;
            case GL_INDEX_LOGIC_OP:
                return CAP_INDEX_LOGIC_OP;
            case GL_STENCIL_TEST:
                return CAP_STENCIL_TEST;
            case GL_DITHER:
                return CAP_DITHER;
            case GL_MULTISAMPLE:
                return CAP_MULTISAMPLE;
            case GL_SAMPLE_ALPHA_TO_COVERAGE:
                return CAP_SAMPLE_ALPHA_TO_COVERAGE;
            case GL_SAMPLE_ALPHA_TO_ONE:
                return CAP_SAMPLE_ALPHA_TO_ONE;
            case GL_SAMPLE_COVERAGE:
                return CAP_SAMPLE_COVERAGE;
            case GL_POINT_SMOOTH:
                return CAP_POINT_SMOOTH;
            case GL_LINE_SMOOTH:
                return CAP_LINE_SMOOTH;
            case GL_LINE_STIPPLE:
                return CAP_LINE_STIPPLE;
            case GL_POLYGON_SMOOTH:
                return CAP_POLYGON_SMOOTH;
            case GL_POLYGON_STIPPLE:
                return CAP_POLYGON_STIPPLE;
            case GL_AUTO_NORMAL:
                return CAP_AUTO_NORMAL;
            default:
                if (cap >= GL_LIGHT0 && cap < GL_LIGHT0 + MAX_LIGHTS) {
                    return CAP_LIGHT0 + (cap - GL_LIGHT0);
                }
                if (cap >= GL_CLIP_PLANE0 && cap < GL_CLIP_PLANE0 + MAX_CLIP_PLANES) {
                    return CAP_CLIP0 + (cap - GL_CLIP_PLANE0);
                }
                return -1;
        }
    }

    /** Texture target bit (TexUnitState.TARGET_*) of a texture enable cap, or 0. */
    private static int textureTargetBit(int cap) {
        switch (cap) {
            case GL_TEXTURE_1D:
                return TexUnitState.TARGET_1D;
            case GL_TEXTURE_2D:
                return TexUnitState.TARGET_2D;
            case GL_TEXTURE_3D:
                return TexUnitState.TARGET_3D;
            case GL_TEXTURE_CUBE_MAP:
                return TexUnitState.TARGET_CUBE;
            default:
                return 0;
        }
    }

    public void setEnable(int cap, boolean on) {
        final int bit = capBit(cap);
        if (bit >= 0) {
            setCap(bit, on);
            return;
        }
        final int target = textureTargetBit(cap);
        if (target != 0) {
            final TexUnitState u = texUnits[activeTextureUnit];
            final int now = on ? u.targetEnable | target : u.targetEnable & ~target;
            if (now != u.targetEnable) {
                u.targetEnable = now;
                if (activeTextureUnit < 2) {
                    uniformsDirty = true;
                }
            }
            return;
        }
        if (cap >= GL_TEXTURE_GEN_S && cap <= GL_TEXTURE_GEN_Q) {
            final TexUnitState u = texUnits[activeTextureUnit];
            final int genBit = 1 << (cap - GL_TEXTURE_GEN_S);
            final int now = on ? u.texGenEnable | genBit : u.texGenEnable & ~genBit;
            if (now != u.texGenEnable) {
                u.texGenEnable = now;
                if (activeTextureUnit == 0) {
                    uniformsDirty = true;
                } else if (on) {
                    warnOnce("texgen.unit", "glTexGen on texture unit " + activeTextureUnit
                            + " is tracked but only unit 0 feeds the fixed-function shader");
                }
            }
            return;
        }
        error(GL_INVALID_ENUM);
    }

    private void setCap(int bit, boolean on) {
        final long m = 1L << bit;
        if (((caps & m) != 0) == on) {
            return;
        }
        caps = on ? caps | m : caps & ~m;
        capChanged(bit, on);
    }

    /** Derived-state bookkeeping for a capability that actually changed (also used after glPopAttrib restores a set). */
    private void capChanged(int bit, boolean on) {
        switch (bit) {
            case CAP_BLEND:
            case CAP_DEPTH_TEST:
            case CAP_CULL_FACE:
            case CAP_ALPHA_TEST:
            case CAP_COLOR_LOGIC_OP:
            case CAP_POLYGON_OFFSET_FILL:
            case CAP_POLYGON_OFFSET_LINE:
            case CAP_POLYGON_OFFSET_POINT:
                pipelineDirty = true;
                break;
            case CAP_LIGHTING:
            case CAP_COLOR_MATERIAL:
            case CAP_NORMALIZE:
            case CAP_RESCALE_NORMAL:
            case CAP_FOG:
            case CAP_LIGHT0:
            case CAP_LIGHT0 + 1:
                uniformsDirty = true;
                break;
            case CAP_STENCIL_TEST:
                if (on) {
                    warnOnce("stencil", "GL_STENCIL_TEST is tracked but not implemented (no stencil support in the pipeline)");
                }
                break;
            default:
                if (on && bit >= CAP_LIGHT0 + 2 && bit < CAP_LIGHT0 + MAX_LIGHTS) {
                    warnOnce("light" + (bit - CAP_LIGHT0), "GL_LIGHT" + (bit - CAP_LIGHT0)
                            + " is tracked but only GL_LIGHT0 and GL_LIGHT1 are evaluated by the fixed-function shader");
                }
                break;
        }
    }

    /** glIsEnabled / glGet(boolean cap) for the caps this class owns; unknown caps raise GL_INVALID_ENUM. */
    public boolean isEnabled(int cap) {
        final int bit = capBit(cap);
        if (bit >= 0) {
            return has(bit);
        }
        final int target = textureTargetBit(cap);
        if (target != 0) {
            return (texUnits[activeTextureUnit].targetEnable & target) != 0;
        }
        if (cap >= GL_TEXTURE_GEN_S && cap <= GL_TEXTURE_GEN_Q) {
            return (texUnits[activeTextureUnit].texGenEnable & (1 << (cap - GL_TEXTURE_GEN_S))) != 0;
        }
        error(GL_INVALID_ENUM);
        return false;
    }

    /** True when {@code cap} is a capability the state machine knows (used by glGet* to decide between a flag and an error). */
    static boolean isKnownCap(int cap) {
        return capBit(cap) >= 0 || textureTargetBit(cap) != 0 || (cap >= GL_TEXTURE_GEN_S && cap <= GL_TEXTURE_GEN_Q);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // current attributes, texture unit
    // ---------------------------------------------------------------------------------------------------------------

    /** glActiveTexture with a 0-based unit; units beyond MAX_TEXTURE_UNITS raise GL_INVALID_ENUM. */
    public void setActiveTexture(int unit) {
        if (unit < 0 || unit >= MAX_TEXTURE_UNITS) {
            error(GL_INVALID_ENUM);
            return;
        }
        activeTextureUnit = unit;
    }

    public void setColor(float r, float g, float b, float a) {
        final float[] c = currentColor;
        c[0] = r;
        c[1] = g;
        c[2] = b;
        c[3] = a;
    }

    public void setNormal(float x, float y, float z) {
        final float[] n = currentNormal;
        n[0] = x;
        n[1] = y;
        n[2] = z;
    }

    /** {@code unit} must be in 0..MAX_TEXTURE_UNITS-1 (validated by the facade). */
    public void setTexCoord(int unit, float s, float t, float r, float q) {
        final float[] c = currentTexCoord[unit];
        c[0] = s;
        c[1] = t;
        c[2] = r;
        c[3] = q;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // matrices
    // ---------------------------------------------------------------------------------------------------------------

    public void setMatrixMode(int mode) {
        if (mode != GL_MODELVIEW && mode != GL_PROJECTION && mode != GL_TEXTURE) {
            error(GL_INVALID_ENUM);
            return;
        }
        matrixMode = mode;
    }

    private MatrixStack currentStack() {
        switch (matrixMode) {
            case GL_PROJECTION:
                return projection;
            case GL_TEXTURE:
                return texMatrix[activeTextureUnit];
            case GL_MODELVIEW:
            default:
                return modelView;
        }
    }

    /** Only the projection and the texture matrices of unit 0/1 are part of the uniform block (modelview is a push constant). */
    private void matrixChanged() {
        if (matrixMode == GL_PROJECTION || (matrixMode == GL_TEXTURE && activeTextureUnit < 2)) {
            uniformsDirty = true;
        }
    }

    public void pushMatrix() {
        if (!currentStack().push()) {
            warnOnce("stack.matrix.overflow." + matrixMode, "GL matrix stack overflow (mode 0x" + Integer.toHexString(matrixMode)
                    + "); further overflows only set GL_STACK_OVERFLOW");
            error(GL_STACK_OVERFLOW);
        }
    }

    public void popMatrix() {
        if (!currentStack().pop()) {
            warnOnce("stack.matrix.underflow." + matrixMode, "GL matrix stack underflow (mode 0x" + Integer.toHexString(matrixMode)
                    + "); further underflows only set GL_STACK_UNDERFLOW");
            error(GL_STACK_UNDERFLOW);
            return;
        }
        matrixChanged();
    }

    public void loadIdentity() {
        currentStack().loadIdentity();
        matrixChanged();
    }

    /** Loads 16 column-major floats from {@code m[0..16)}. */
    public void loadMatrix(float[] m) {
        currentStack().load(m);
        matrixChanged();
    }

    /** current = current * m for 16 column-major floats. */
    public void multMatrix(float[] m) {
        currentStack().mult(m);
        matrixChanged();
    }

    public void translate(float x, float y, float z) {
        currentStack().translate(x, y, z);
        matrixChanged();
    }

    public void scale(float x, float y, float z) {
        currentStack().scale(x, y, z);
        matrixChanged();
    }

    public void rotate(float angle, float x, float y, float z) {
        currentStack().rotate(angle, x, y, z);
        matrixChanged();
    }

    public void ortho(double l, double r, double b, double t, double n, double f) {
        if (!currentStack().ortho(l, r, b, t, n, f)) {
            error(GL_INVALID_VALUE);
            return;
        }
        matrixChanged();
    }

    public void frustum(double l, double r, double b, double t, double n, double f) {
        if (!currentStack().frustum(l, r, b, t, n, f)) {
            error(GL_INVALID_VALUE);
            return;
        }
        matrixChanged();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // blending, logic op, alpha test
    // ---------------------------------------------------------------------------------------------------------------

    public void setBlendFunc(int src, int dst) {
        setBlendFuncSeparate(src, dst, src, dst);
    }

    public void setBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (!validBlendFactor(srcRgb, true) || !validBlendFactor(dstRgb, false) || !validBlendFactor(srcAlpha, true)
                || !validBlendFactor(dstAlpha, false)) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (srcRgb != blendSrcRgb || dstRgb != blendDstRgb || srcAlpha != blendSrcAlpha || dstAlpha != blendDstAlpha) {
            blendSrcRgb = srcRgb;
            blendDstRgb = dstRgb;
            blendSrcAlpha = srcAlpha;
            blendDstAlpha = dstAlpha;
            pipelineDirty = true;
        }
    }

    public void setBlendEquation(int mode) {
        setBlendEquationSeparate(mode, mode);
    }

    public void setBlendEquationSeparate(int modeRgb, int modeAlpha) {
        if (!validBlendEquation(modeRgb) || !validBlendEquation(modeAlpha)) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (modeRgb != blendEqRgb || modeAlpha != blendEqAlpha) {
            blendEqRgb = modeRgb;
            blendEqAlpha = modeAlpha;
            pipelineDirty = true;
        }
    }

    public void setBlendColor(float r, float g, float b, float a) {
        blendColor[0] = clamp01(r);
        blendColor[1] = clamp01(g);
        blendColor[2] = clamp01(b);
        blendColor[3] = clamp01(a);
    }

    public void setLogicOp(int opcode) {
        if (opcode < GL_CLEAR || opcode > GL_SET) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (logicOp != opcode) {
            logicOp = opcode;
            pipelineDirty = true;
        }
    }

    public void setAlphaFunc(int func, float ref) {
        if (!validCompareFunc(func)) {
            error(GL_INVALID_ENUM);
            return;
        }
        final float r = clamp01(ref);
        if (func != alphaFunc) {
            alphaFunc = func;
            pipelineDirty = true;
            uniformsDirty = true;
        }
        if (r != alphaRef) {
            alphaRef = r;
            uniformsDirty = true;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // depth, color mask, buffers
    // ---------------------------------------------------------------------------------------------------------------

    public void setDepthFunc(int func) {
        if (!validCompareFunc(func)) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (func != depthFunc) {
            depthFunc = func;
            pipelineDirty = true;
        }
    }

    public void setDepthMask(boolean mask) {
        if (mask != depthMask) {
            depthMask = mask;
            pipelineDirty = true;
        }
    }

    /** glDepthRange: Vulkan viewports here always use 0..1, other ranges are tracked and logged once. */
    public void setDepthRange(double near, double far) {
        depthNear = Math.max(0.0, Math.min(1.0, near));
        depthFar = Math.max(0.0, Math.min(1.0, far));
        if (depthNear != 0.0 || depthFar != 1.0) {
            warnOnce("depthRange", "glDepthRange(" + near + ", " + far + ") is tracked but the Vulkan viewport depth range stays 0..1");
        }
    }

    public void setClearDepth(double depth) {
        clearDepth = (float) Math.max(0.0, Math.min(1.0, depth));
    }

    public void setClearColor(float r, float g, float b, float a) {
        clearColor[0] = clamp01(r);
        clearColor[1] = clamp01(g);
        clearColor[2] = clamp01(b);
        clearColor[3] = clamp01(a);
    }

    public void setClearStencil(int s) {
        clearStencil = s;
    }

    public void setColorMask(boolean r, boolean g, boolean b, boolean a) {
        final int mask = (r ? VK10.VK_COLOR_COMPONENT_R_BIT : 0) | (g ? VK10.VK_COLOR_COMPONENT_G_BIT : 0)
                | (b ? VK10.VK_COLOR_COMPONENT_B_BIT : 0) | (a ? VK10.VK_COLOR_COMPONENT_A_BIT : 0);
        if (mask != colorMask) {
            colorMask = mask;
            pipelineDirty = true;
        }
    }

    private static boolean validDrawBuffer(int mode) {
        switch (mode) {
            case GL_NONE:
            case GL_FRONT:
            case GL_BACK:
            case GL_FRONT_AND_BACK:
            case GL_FRONT_LEFT:
            case GL_BACK_LEFT:
            case GL_LEFT:
                return true;
            default:
                return false;
        }
    }

    /** glDrawBuffer: GL_NONE disables all color writes, every other valid mode is the single offscreen color buffer. */
    public void setDrawBuffer(int mode) {
        if (!validDrawBuffer(mode)) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (mode != drawBuffer) {
            drawBuffer = mode;
            pipelineDirty = true;
        }
    }

    public void setReadBuffer(int mode) {
        if (mode == GL_NONE || !validDrawBuffer(mode)) {
            error(GL_INVALID_ENUM);
            return;
        }
        readBuffer = mode;
    }

    public void setStencilFunc(int func, int ref, int mask) {
        if (!validCompareFunc(func)) {
            error(GL_INVALID_ENUM);
            return;
        }
        stencilFunc = func;
        stencilRef = ref;
        stencilValueMask = mask;
    }

    public void setStencilMask(int mask) {
        stencilWriteMask = mask;
    }

    public void setStencilOp(int fail, int zfail, int zpass) {
        stencilFail = fail;
        stencilZFail = zfail;
        stencilZPass = zpass;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // rasterization
    // ---------------------------------------------------------------------------------------------------------------

    public void setCullFace(int mode) {
        if (mode != GL_FRONT && mode != GL_BACK && mode != GL_FRONT_AND_BACK) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (mode != cullFaceMode) {
            cullFaceMode = mode;
            pipelineDirty = true;
        }
    }

    public void setFrontFace(int mode) {
        if (mode != GL_CW && mode != GL_CCW) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (mode != frontFace) {
            frontFace = mode;
            pipelineDirty = true;
        }
    }

    public void setPolygonMode(int face, int mode) {
        if ((face != GL_FRONT && face != GL_BACK && face != GL_FRONT_AND_BACK)
                || (mode != GL_POINT && mode != GL_LINE && mode != GL_FILL)) {
            error(GL_INVALID_ENUM);
            return;
        }
        final int front = face == GL_BACK ? polyModeFront : mode;
        final int back = face == GL_FRONT ? polyModeBack : mode;
        if (front != polyModeFront || back != polyModeBack) {
            polyModeFront = front;
            polyModeBack = back;
            pipelineDirty = true;
        }
    }

    public void setPolygonOffset(float factor, float units) {
        if (factor != polyOffsetFactor || units != polyOffsetUnits) {
            polyOffsetFactor = factor;
            polyOffsetUnits = units;
            pipelineDirty = true;
        }
    }

    public void setLineWidth(float width) {
        if (!(width > 0f)) {
            error(GL_INVALID_VALUE);
            return;
        }
        lineWidth = width;
    }

    public void setPointSize(float size) {
        if (!(size > 0f)) {
            error(GL_INVALID_VALUE);
            return;
        }
        pointSize = size;
    }

    public void setShadeModel(int mode) {
        if (mode != GL_FLAT && mode != GL_SMOOTH) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (mode != shadeModel) {
            shadeModel = mode;
            pipelineDirty = true;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // viewport, scissor, clear
    // ---------------------------------------------------------------------------------------------------------------

    public void setViewport(int x, int y, int w, int h) {
        if (w < 0 || h < 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = w;
        viewport[3] = h;
    }

    /** glScissor only moves the box; the scissor test itself is switched by glEnable/glDisable(GL_SCISSOR_TEST). */
    public void setScissor(int x, int y, int w, int h) {
        if (w < 0 || h < 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        scissorBox[0] = x;
        scissorBox[1] = y;
        scissorBox[2] = w;
        scissorBox[3] = h;
    }

    /** glClear: honours the scissor test, the color write mask and the depth mask; stencil/accum bits are ignored. */
    public void clear(int mask) {
        if ((mask & ~(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT | GL_ACCUM_BUFFER_BIT)) != 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        final boolean color = (mask & GL_COLOR_BUFFER_BIT) != 0 && colorMask != 0 && drawBuffer != GL_NONE;
        final boolean depth = (mask & GL_DEPTH_BUFFER_BIT) != 0 && depthMask;
        if (!color && !depth) {
            return;
        }
        final int fbW = RenderCore.framebufferWidth();
        final int fbH = RenderCore.framebufferHeight();
        final int x, y, w, h; // GL window coordinates
        if (has(CAP_SCISSOR_TEST)) {
            if (!computeScissor(fbW, fbH)) {
                return;
            }
            x = scX;
            y = scY;
            w = scW;
            h = scH;
        } else {
            if (fbW <= 0 || fbH <= 0) {
                return;
            }
            x = 0;
            y = 0;
            w = fbW;
            h = fbH;
        }
        if (color && colorMask != 0xF) {
            warnOnce("clear.colorMask", "glClear with a partial color write mask clears all color channels");
        }
        // earlier draws must be recorded before the clear
        flushPending();
        RenderCore.clear(color, depth, clearColor[0], clearColor[1], clearColor[2], clearColor[3], clearDepth,
                x, fbH - y - h, w, h);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // lighting
    // ---------------------------------------------------------------------------------------------------------------

    /** Number of values glLight reads for {@code pname}, 0 for an unknown pname. */
    public static int lightParamCount(int pname) {
        switch (pname) {
            case GL_AMBIENT:
            case GL_DIFFUSE:
            case GL_SPECULAR:
            case GL_POSITION:
                return 4;
            case GL_SPOT_DIRECTION:
                return 3;
            case GL_SPOT_EXPONENT:
            case GL_SPOT_CUTOFF:
            case GL_CONSTANT_ATTENUATION:
            case GL_LINEAR_ATTENUATION:
            case GL_QUADRATIC_ATTENUATION:
                return 1;
            default:
                return 0;
        }
    }

    /** True when the integer form of the light parameter is a color that GL scales into [-1, 1]. */
    public static boolean lightParamIsColor(int pname) {
        return pname == GL_AMBIENT || pname == GL_DIFFUSE || pname == GL_SPECULAR;
    }

    /** glLight with the 0-based light index (validated against MAX_LIGHTS here) and {@link #lightParamCount} values. */
    public void setLight(int light, int pname, float[] v) {
        if (light < 0 || light >= MAX_LIGHTS || lightParamCount(pname) == 0) {
            error(GL_INVALID_ENUM);
            return;
        }
        final LightState l = lights[light];
        boolean changed;
        switch (pname) {
            case GL_AMBIENT:
                changed = assign(l.ambient, v, 4);
                break;
            case GL_DIFFUSE:
                changed = assign(l.diffuse, v, 4);
                break;
            case GL_SPECULAR:
                changed = assign(l.specular, v, 4);
                break;
            case GL_POSITION: {
                // transformed by the modelview current at glLight time (eye space)
                final float[] m = modelView.top();
                final float x = v[0], y = v[1], z = v[2], w = v[3];
                final float[] p = POS_SCRATCH;
                p[0] = m[0] * x + m[4] * y + m[8] * z + m[12] * w;
                p[1] = m[1] * x + m[5] * y + m[9] * z + m[13] * w;
                p[2] = m[2] * x + m[6] * y + m[10] * z + m[14] * w;
                p[3] = m[3] * x + m[7] * y + m[11] * z + m[15] * w;
                changed = assign(l.position, p, 4);
                break;
            }
            case GL_SPOT_DIRECTION: {
                // transformed like a normal: by the inverse transpose of the modelview (identity when singular)
                final float[] inv = INV_SCRATCH;
                if (!Mat4.invert(modelView.top(), inv)) {
                    Mat4.identity(inv);
                }
                final float[] d = POS_SCRATCH;
                for (int i = 0; i < 3; i++) {
                    d[i] = inv[i * 4] * v[0] + inv[i * 4 + 1] * v[1] + inv[i * 4 + 2] * v[2];
                }
                changed = assign(l.spotDirection, d, 3);
                break;
            }
            case GL_SPOT_EXPONENT:
                if (v[0] < 0f || v[0] > 128f) {
                    error(GL_INVALID_VALUE);
                    return;
                }
                changed = l.spotExponent != v[0];
                l.spotExponent = v[0];
                break;
            case GL_SPOT_CUTOFF:
                if (v[0] != 180f && (v[0] < 0f || v[0] > 90f)) {
                    error(GL_INVALID_VALUE);
                    return;
                }
                changed = l.spotCutoff != v[0];
                l.spotCutoff = v[0];
                break;
            case GL_CONSTANT_ATTENUATION:
            case GL_LINEAR_ATTENUATION:
            case GL_QUADRATIC_ATTENUATION:
                if (v[0] < 0f) {
                    error(GL_INVALID_VALUE);
                    return;
                }
                changed = true;
                if (pname == GL_CONSTANT_ATTENUATION) l.constantAttenuation = v[0];
                else if (pname == GL_LINEAR_ATTENUATION) l.linearAttenuation = v[0];
                else l.quadraticAttenuation = v[0];
                break;
            default:
                return;
        }
        // only lights 0 and 1 are part of the uniform block, and only position/diffuse/ambient matter to the shader
        if (changed && light < 2 && (pname == GL_AMBIENT || pname == GL_DIFFUSE || pname == GL_POSITION)) {
            uniformsDirty = true;
        }
    }

    private static final float[] POS_SCRATCH = new float[4];
    private static final float[] INV_SCRATCH = new float[16];

    public static int lightModelParamCount(int pname) {
        switch (pname) {
            case GL_LIGHT_MODEL_AMBIENT:
                return 4;
            case GL_LIGHT_MODEL_LOCAL_VIEWER:
            case GL_LIGHT_MODEL_TWO_SIDE:
            case GL_LIGHT_MODEL_COLOR_CONTROL:
                return 1;
            default:
                return 0;
        }
    }

    public void setLightModel(int pname, float[] v) {
        switch (pname) {
            case GL_LIGHT_MODEL_AMBIENT:
                if (assign(lightModelAmbient, v, 4)) {
                    uniformsDirty = true;
                }
                break;
            case GL_LIGHT_MODEL_LOCAL_VIEWER:
                lightModelLocalViewer = v[0] != 0f;
                break;
            case GL_LIGHT_MODEL_TWO_SIDE:
                lightModelTwoSide = v[0] != 0f;
                break;
            case GL_LIGHT_MODEL_COLOR_CONTROL: {
                final int m = (int) v[0];
                if (m != GL_SINGLE_COLOR && m != GL_SEPARATE_SPECULAR_COLOR) {
                    error(GL_INVALID_ENUM);
                    return;
                }
                lightModelColorControl = m;
                break;
            }
            default:
                error(GL_INVALID_ENUM);
                break;
        }
    }

    public static int materialParamCount(int pname) {
        switch (pname) {
            case GL_AMBIENT:
            case GL_DIFFUSE:
            case GL_SPECULAR:
            case GL_EMISSION:
            case GL_AMBIENT_AND_DIFFUSE:
                return 4;
            case GL_COLOR_INDEXES:
                return 3;
            case GL_SHININESS:
                return 1;
            default:
                return 0;
        }
    }

    public static boolean materialParamIsColor(int pname) {
        return pname == GL_AMBIENT || pname == GL_DIFFUSE || pname == GL_SPECULAR || pname == GL_EMISSION
                || pname == GL_AMBIENT_AND_DIFFUSE;
    }

    public void setMaterial(int face, int pname, float[] v) {
        if ((face != GL_FRONT && face != GL_BACK && face != GL_FRONT_AND_BACK) || materialParamCount(pname) == 0) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (pname == GL_SHININESS && (v[0] < 0f || v[0] > 128f)) {
            error(GL_INVALID_VALUE);
            return;
        }
        if (face != GL_BACK) {
            // the shader evaluates the front material for every face
            boolean changed = false;
            switch (pname) {
                case GL_AMBIENT:
                    changed = assign(material.ambient, v, 4);
                    break;
                case GL_DIFFUSE:
                    changed = assign(material.diffuse, v, 4);
                    break;
                case GL_AMBIENT_AND_DIFFUSE:
                    changed = assign(material.ambient, v, 4);
                    changed |= assign(material.diffuse, v, 4);
                    break;
                case GL_SPECULAR:
                    assign(material.specular, v, 4);
                    break;
                case GL_EMISSION:
                    changed = assign(material.emission, v, 4);
                    break;
                case GL_SHININESS:
                    material.shininess = v[0];
                    break;
                default:
                    break;
            }
            if (changed) {
                uniformsDirty = true;
            }
        }
        if (face != GL_FRONT) {
            switch (pname) {
                case GL_AMBIENT:
                    assign(materialBack.ambient, v, 4);
                    break;
                case GL_DIFFUSE:
                    assign(materialBack.diffuse, v, 4);
                    break;
                case GL_AMBIENT_AND_DIFFUSE:
                    assign(materialBack.ambient, v, 4);
                    assign(materialBack.diffuse, v, 4);
                    break;
                case GL_SPECULAR:
                    assign(materialBack.specular, v, 4);
                    break;
                case GL_EMISSION:
                    assign(materialBack.emission, v, 4);
                    break;
                case GL_SHININESS:
                    materialBack.shininess = v[0];
                    break;
                default:
                    break;
            }
        }
    }

    public void setColorMaterial(int face, int mode) {
        if ((face != GL_FRONT && face != GL_BACK && face != GL_FRONT_AND_BACK)
                || (mode != GL_EMISSION && mode != GL_AMBIENT && mode != GL_DIFFUSE && mode != GL_SPECULAR
                && mode != GL_AMBIENT_AND_DIFFUSE)) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (face != colorMaterialFace || mode != colorMaterialMode) {
            colorMaterialFace = face;
            colorMaterialMode = mode;
            uniformsDirty = true;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // fog
    // ---------------------------------------------------------------------------------------------------------------

    public static int fogParamCount(int pname) {
        switch (pname) {
            case GL_FOG_COLOR:
                return 4;
            case GL_FOG_MODE:
            case GL_FOG_DENSITY:
            case GL_FOG_START:
            case GL_FOG_END:
            case GL_FOG_INDEX:
            case GL_FOG_DISTANCE_MODE_NV:
                return 1;
            default:
                return 0;
        }
    }

    public static boolean fogParamIsColor(int pname) {
        return pname == GL_FOG_COLOR;
    }

    public void setFog(int pname, float[] v) {
        switch (pname) {
            case GL_FOG_MODE: {
                final int m = (int) v[0];
                if (m != GL_EXP && m != GL_EXP2 && m != GL_LINEAR) {
                    error(GL_INVALID_ENUM);
                    return;
                }
                if (fog.mode != m) {
                    fog.mode = m;
                    uniformsDirty = true;
                }
                break;
            }
            case GL_FOG_DENSITY:
                if (v[0] < 0f) {
                    error(GL_INVALID_VALUE);
                    return;
                }
                if (fog.density != v[0]) {
                    fog.density = v[0];
                    uniformsDirty = true;
                }
                break;
            case GL_FOG_START:
                if (fog.start != v[0]) {
                    fog.start = v[0];
                    uniformsDirty = true;
                }
                break;
            case GL_FOG_END:
                if (fog.end != v[0]) {
                    fog.end = v[0];
                    uniformsDirty = true;
                }
                break;
            case GL_FOG_INDEX:
                fog.index = v[0];
                break;
            case GL_FOG_COLOR:
                if (assign(fog.color, clampedColor(v), 4)) {
                    uniformsDirty = true;
                }
                break;
            case GL_FOG_DISTANCE_MODE_NV: {
                // GL_NV_fog_distance: EYE_RADIAL_NV selects the euclidean distance, the plane modes use |z|
                final int m = (int) v[0];
                if (m != GL_EYE_PLANE_ABSOLUTE_NV && m != GL_EYE_RADIAL_NV && m != GL_EYE_PLANE) {
                    error(GL_INVALID_ENUM);
                    return;
                }
                if (fog.distanceMode != m) {
                    final boolean radialBefore = fog.distanceMode == GL_EYE_RADIAL_NV;
                    fog.distanceMode = m;
                    if (radialBefore != (m == GL_EYE_RADIAL_NV)) {
                        uniformsDirty = true;
                    }
                }
                break;
            }
            default:
                error(GL_INVALID_ENUM);
                break;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // texture environment, texture coordinate generation, hints
    // ---------------------------------------------------------------------------------------------------------------

    /** Number of values glTexEnv reads for (target, pname); 0 for an unknown combination. */
    public static int texEnvParamCount(int target, int pname) {
        switch (target) {
            case GL_TEXTURE_ENV:
                switch (pname) {
                    case GL_TEXTURE_ENV_COLOR:
                        return 4;
                    case GL_TEXTURE_ENV_MODE:
                    case GL_COMBINE_RGB:
                    case GL_COMBINE_ALPHA:
                    case GL_RGB_SCALE:
                    case GL_ALPHA_SCALE:
                    case GL_SOURCE0_RGB:
                    case GL_SOURCE1_RGB:
                    case GL_SOURCE2_RGB:
                    case GL_SOURCE0_ALPHA:
                    case GL_SOURCE1_ALPHA:
                    case GL_SOURCE2_ALPHA:
                    case GL_OPERAND0_RGB:
                    case GL_OPERAND1_RGB:
                    case GL_OPERAND2_RGB:
                    case GL_OPERAND0_ALPHA:
                    case GL_OPERAND1_ALPHA:
                    case GL_OPERAND2_ALPHA:
                        return 1;
                    default:
                        return 0;
                }
            case GL_TEXTURE_FILTER_CONTROL:
                return pname == GL_TEXTURE_LOD_BIAS ? 1 : 0;
            case GL_POINT_SPRITE:
                return pname == GL_COORD_REPLACE ? 1 : 0;
            default:
                return 0;
        }
    }

    public static boolean texEnvParamIsColor(int target, int pname) {
        return target == GL_TEXTURE_ENV && pname == GL_TEXTURE_ENV_COLOR;
    }

    private static boolean validTexEnvMode(int m) {
        return m == GL_MODULATE || m == GL_REPLACE || m == GL_DECAL || m == GL_BLEND || m == GL_ADD || m == GL_COMBINE;
    }

    public void setTexEnv(int target, int pname, float[] v) {
        if (texEnvParamCount(target, pname) == 0) {
            error(GL_INVALID_ENUM);
            return;
        }
        final TexUnitState u = texUnits[activeTextureUnit];
        final boolean shaderUnit = activeTextureUnit < 2;
        if (target == GL_TEXTURE_FILTER_CONTROL) {
            u.lodBias = v[0];
            return;
        }
        if (target == GL_POINT_SPRITE) {
            u.coordReplace = v[0] != 0f;
            return;
        }
        final int e = (int) v[0];
        switch (pname) {
            case GL_TEXTURE_ENV_MODE:
                if (!validTexEnvMode(e)) {
                    error(GL_INVALID_ENUM);
                    return;
                }
                if (u.envMode != e) {
                    u.envMode = e;
                    if (shaderUnit) {
                        uniformsDirty = true;
                    }
                }
                break;
            case GL_TEXTURE_ENV_COLOR:
                if (assign(u.envColor, clampedColor(v), 4) && activeTextureUnit == 0) {
                    uniformsDirty = true;
                }
                break;
            case GL_COMBINE_RGB:
                u.combineRgb = e;
                break;
            case GL_COMBINE_ALPHA:
                u.combineAlpha = e;
                break;
            case GL_RGB_SCALE:
            case GL_ALPHA_SCALE:
                if (v[0] != 1f && v[0] != 2f && v[0] != 4f) {
                    error(GL_INVALID_VALUE);
                    return;
                }
                if (pname == GL_RGB_SCALE) u.rgbScale = v[0];
                else u.alphaScale = v[0];
                break;
            case GL_SOURCE0_RGB:
                u.sourceRgb[0] = e;
                break;
            case GL_SOURCE1_RGB:
                u.sourceRgb[1] = e;
                break;
            case GL_SOURCE2_RGB:
                u.sourceRgb[2] = e;
                break;
            case GL_SOURCE0_ALPHA:
                u.sourceAlpha[0] = e;
                break;
            case GL_SOURCE1_ALPHA:
                u.sourceAlpha[1] = e;
                break;
            case GL_SOURCE2_ALPHA:
                u.sourceAlpha[2] = e;
                break;
            case GL_OPERAND0_RGB:
                u.operandRgb[0] = e;
                break;
            case GL_OPERAND1_RGB:
                u.operandRgb[1] = e;
                break;
            case GL_OPERAND2_RGB:
                u.operandRgb[2] = e;
                break;
            case GL_OPERAND0_ALPHA:
                u.operandAlpha[0] = e;
                break;
            case GL_OPERAND1_ALPHA:
                u.operandAlpha[1] = e;
                break;
            case GL_OPERAND2_ALPHA:
                u.operandAlpha[2] = e;
                break;
            default:
                break;
        }
    }

    public static int texGenParamCount(int pname) {
        switch (pname) {
            case GL_TEXTURE_GEN_MODE:
                return 1;
            case GL_OBJECT_PLANE:
            case GL_EYE_PLANE:
                return 4;
            default:
                return 0;
        }
    }

    public void setTexGen(int coord, int pname, float[] v) {
        final int c = coord - GL_S;
        if (c < 0 || c > 3 || texGenParamCount(pname) == 0) {
            error(GL_INVALID_ENUM);
            return;
        }
        final TexUnitState u = texUnits[activeTextureUnit];
        boolean changed = false;
        switch (pname) {
            case GL_TEXTURE_GEN_MODE: {
                final int m = (int) v[0];
                final boolean ok = m == GL_EYE_LINEAR || m == GL_OBJECT_LINEAR
                        || (m == GL_SPHERE_MAP && c < 2)
                        || ((m == GL_NORMAL_MAP || m == GL_REFLECTION_MAP) && c < 3);
                if (!ok) {
                    error(GL_INVALID_ENUM);
                    return;
                }
                if (m == GL_NORMAL_MAP || m == GL_REFLECTION_MAP) {
                    warnOnce("texgen.cubemap", "GL_NORMAL_MAP/GL_REFLECTION_MAP texture coordinate generation is not supported "
                            + "(no cube maps); GL_OBJECT_LINEAR is used instead");
                }
                changed = u.genMode[c] != m;
                u.genMode[c] = m;
                break;
            }
            case GL_OBJECT_PLANE:
                changed = assign(u.objectPlane[c], v, 4);
                break;
            case GL_EYE_PLANE: {
                // GL stores the plane multiplied by the inverse of the modelview current at glTexGen time
                final float[] inv = INV_SCRATCH;
                final float[] p = POS_SCRATCH;
                if (Mat4.invert(modelView.top(), inv)) {
                    for (int j = 0; j < 4; j++) {
                        p[j] = v[0] * inv[j * 4] + v[1] * inv[j * 4 + 1] + v[2] * inv[j * 4 + 2] + v[3] * inv[j * 4 + 3];
                    }
                } else {
                    warnOnce("texgen.singular", "glTexGen(GL_EYE_PLANE) with a singular modelview matrix: plane stored untransformed");
                    System.arraycopy(v, 0, p, 0, 4);
                }
                changed = assign(u.eyePlane[c], p, 4);
                break;
            }
            default:
                return;
        }
        if (changed && activeTextureUnit == 0) {
            uniformsDirty = true;
        } else if (changed) {
            warnOnce("texgen.unit", "glTexGen on texture unit " + activeTextureUnit
                    + " is tracked but only unit 0 feeds the fixed-function shader");
        }
    }

    /** Index into {@link #hints} of a glHint target, -1 when unknown. */
    static int hintIndex(int target) {
        switch (target) {
            case GL_PERSPECTIVE_CORRECTION_HINT:
                return 0;
            case GL_POINT_SMOOTH_HINT:
                return 1;
            case GL_LINE_SMOOTH_HINT:
                return 2;
            case GL_POLYGON_SMOOTH_HINT:
                return 3;
            case GL_FOG_HINT:
                return 4;
            case GL_GENERATE_MIPMAP_HINT:
                return 5;
            case GL_TEXTURE_COMPRESSION_HINT:
                return 6;
            default:
                return -1;
        }
    }

    public void setHint(int target, int mode) {
        final int i = hintIndex(target);
        if (i < 0 || (mode != GL_DONT_CARE && mode != GL_FASTEST && mode != GL_NICEST)) {
            error(GL_INVALID_ENUM);
            return;
        }
        hints[i] = mode;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // attribute stack
    // ---------------------------------------------------------------------------------------------------------------

    public void pushAttrib(int mask) {
        if (!attribStack.push(this, mask)) {
            warnOnce("stack.attrib.overflow", "GL attribute stack overflow (depth " + ATTRIB_STACK_DEPTH
                    + "); further overflows only set GL_STACK_OVERFLOW");
            error(GL_STACK_OVERFLOW);
        }
    }

    public void popAttrib() {
        if (!attribStack.pop(this)) {
            warnOnce("stack.attrib.underflow", "GL attribute stack underflow; further underflows only set GL_STACK_UNDERFLOW");
            error(GL_STACK_UNDERFLOW);
        }
    }

    /** AttribStack calls this after it restored state: everything derived is recomputed lazily. */
    void attribRestored(boolean uniformState) {
        pipelineDirty = true;
        if (uniformState) {
            uniformsDirty = true;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // queries (glGet*): the values come from StateQuery, the facade adds the buffer/typing semantics
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Values of the GL state variable {@code pname} (as doubles) into {@code out}; returns the number of values, or -1 when the
     * pname is not owned by this class (the facade then asks TextureManager/ClientState and finally raises GL_INVALID_ENUM).
     */
    public int query(int pname, double[] out) {
        return StateQuery.get(this, pname, out);
    }

    /** glGetLight: values of one light parameter, -1 (and GL_INVALID_ENUM) for an unknown light/pname. */
    public int queryLight(int light, int pname, float[] out) {
        return StateQuery.light(this, light, pname, out);
    }

    public int queryMaterial(int face, int pname, float[] out) {
        return StateQuery.material(this, face, pname, out);
    }

    public int queryTexEnv(int target, int pname, float[] out) {
        return StateQuery.texEnv(this, target, pname, out);
    }

    public int queryTexGen(int coord, int pname, float[] out) {
        return StateQuery.texGen(this, coord, pname, out);
    }

    /** True for a state variable whose integer query is a color/normal that GL maps linearly onto the int range. */
    public static boolean isColorLikeState(int pname) {
        return StateQuery.isColorLike(pname);
    }

    /** NaN-safe: NaN clamps to 0 so a bad value can never poison the uniform block. */
    static float clamp01(float v) {
        return v > 0f ? (v < 1f ? v : 1f) : 0f;
    }

    private static final float[] COLOR_SCRATCH = new float[4];

    /**
     * GL clamps GL_FOG_COLOR and GL_TEXTURE_ENV_COLOR to [0, 1] when they are specified (GL 1.5 sections 3.10 and 3.8.9), so a
     * later glGet and the shaders (mix(fogColor, ...), TEXENV_BLEND) only ever see clamped components. Returns the shared
     * scratch array (render thread only, no allocation); the caller copies it immediately.
     */
    private static float[] clampedColor(float[] v) {
        final float[] c = COLOR_SCRATCH;
        c[0] = clamp01(v[0]);
        c[1] = clamp01(v[1]);
        c[2] = clamp01(v[2]);
        c[3] = clamp01(v[3]);
        return c;
    }

    DeviceInfo deviceInfoOrNull() {
        return deviceInfo();
    }
}
