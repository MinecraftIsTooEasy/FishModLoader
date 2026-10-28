package net.xiaoyu233.fml.vulkan.render.state;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.render.buffer.StaticMesh;
import net.xiaoyu233.fml.vulkan.render.gpu.Program;
import net.xiaoyu233.fml.vulkan.render.legacy.StateMirror;

/**
 * Typed render state of the native renderer (ARCHITECTURE-NATIVE 1.7 / 5.3); render thread only, no checks (hot path). It is not a GL
 * emulation: it has exactly the vocabulary MITE uses, no names, queries or draw-time validation. A state change only invalidates the
 * cached {@link #materialKey()}; vertices are finalised on the CPU by {@code NativeTess}.
 * <p>
 * Every setter reports the {@link LegacyGroups legacy group} it touched to {@link StateMirror#onNativeChanged(int)} (the only link to the
 * GL safety net, 1.16.3) and accumulates it in {@link #dirtyLegacyGroups()}. Values that equal the current state are not reported.
 * The state persists across phases and frames exactly like GL state (exit-state rule, 1.7).
 */
public final class Draw {
    private static final PoseStack POSE = new PoseStack(64);
    private static final PoseStack PROJECTION = new PoseStack(32);
    private static final PoseStack UV = new PoseStack(32);

    // ------------------------------------------------------------------------------------------------ current vertex attributes
    private static float colR = 1f, colG = 1f, colB = 1f, colA = 1f;
    private static int colAbgr = 0xFFFFFFFF;
    private static float nrmX, nrmY, nrmZ = 1f;
    private static float lightU, lightV;
    private static int lightPacked;
    private static float ovR, ovG, ovB, ovA;
    private static int ovArgb;

    // ------------------------------------------------------------------------------------------------ texture units
    private static int activeUnit;
    private static final int[] boundTexture = new int[2];
    private static final boolean[] texturing = new boolean[2];

    // ------------------------------------------------------------------------------------------------ fixed function state
    private static boolean blend;
    private static BlendFactor blendSrc = BlendFactor.ONE, blendDst = BlendFactor.ZERO;
    private static boolean alphaTest;
    private static float alphaRef;
    private static boolean depthTest;
    private static DepthFunc depthFunc = DepthFunc.LESS;
    private static boolean depthMask = true;
    private static boolean cull;
    private static Face cullFace = Face.BACK;
    private static int colorMask = 7;
    private static boolean polyOffset;
    private static float polyFactor, polyUnits;
    private static float lineWidth = 1f;
    private static boolean logicOp;
    private static boolean smooth = true;
    private static boolean fog;
    private static FogMode fogMode = FogMode.EXP;
    private static float fogStart, fogEnd = 1f, fogDensity = 1f;
    private static final float[] fogColor = new float[4];
    private static int fogVersion;
    private static boolean lighting;
    private static boolean rescale, normalize;
    private static boolean colorMaterial;
    private static ColorMaterial colorMaterialMode = ColorMaterial.AMBIENT_AND_DIFFUSE;
    /** Enable flags and eye-space directions of lights 0 and 1 (written by {@link Lighting}). */
    static boolean light0, light1;
    static final float[] LIGHT_DIR0 = new float[3], LIGHT_DIR1 = new float[3];

    // ------------------------------------------------------------------------------------------------ bookkeeping
    private static int dirtyLegacy;
    private static boolean suppressDirty;
    private static boolean keyValid;
    private static long cachedKey;
    private static StaticDrawHandler staticHandler;

    private static final int ATTRIB_DEPTH = 32;
    private static final Snapshot[] ATTRIBS = new Snapshot[ATTRIB_DEPTH];
    private static int attribTop;
    private static boolean attribWarned;

    static {
        for (int i = 0; i < ATTRIB_DEPTH; i++) {
            ATTRIBS[i] = new Snapshot();
        }
    }

    private Draw() {
    }

    // ================================================================================================ matrices

    public static PoseStack pose() {
        return POSE;
    }

    public static PoseStack projection() {
        return PROJECTION;
    }

    /** Texture matrix of unit 0; applied to the uv of every vertex at {@code NativeTess.draw}. */
    public static PoseStack uvMatrix() {
        return UV;
    }

    public static void pushPose() {
        POSE.push();
    }

    public static void popPose() {
        POSE.pop();
    }

    public static void translate(float x, float y, float z) {
        POSE.translate(x, y, z);
    }

    public static void translate(double x, double y, double z) {
        POSE.translate(x, y, z);
    }

    public static void rotate(float degrees, float x, float y, float z) {
        POSE.rotate(degrees, x, y, z);
    }

    public static void scale(float x, float y, float z) {
        POSE.scale(x, y, z);
    }

    public static void scale(double x, double y, double z) {
        POSE.scale(x, y, z);
    }

    // ================================================================================================ dirty tracking

    private static void touch(int group) {
        if (!suppressDirty) {
            dirtyLegacy |= group;
            StateMirror.onNativeChanged(group);
        }
    }

    /** For {@link Lighting}: reports a change of the lighting group. */
    static void touchLighting() {
        keyValid = false;
        touch(LegacyGroups.LIGHTING);
    }

    /** Groups changed since the last {@link #clearDirtyLegacyGroups()} ({@link LegacyGroups} bits). */
    public static int dirtyLegacyGroups() {
        return dirtyLegacy;
    }

    public static void clearDirtyLegacyGroups() {
        dirtyLegacy = 0;
    }

    /**
     * Brackets the state writes of {@code StateMirror.syncLegacyToNative}: changes made in between come from the emulation and are not
     * reported back (no ping-pong). Not re-entrant.
     */
    public static void beginLegacyPull() {
        suppressDirty = true;
    }

    public static void endLegacyPull() {
        suppressDirty = false;
        keyValid = false;
    }

    private static void pullLegacy() {
        if (StateMirror.legacyDirty != 0) {
            StateMirror.syncLegacyToNative();
        }
    }

    // ================================================================================================ current attributes

    public static void color(float r, float g, float b, float a) {
        pullLegacy();
        colR = r;
        colG = g;
        colB = b;
        colA = a;
        colAbgr = packUnit(r, g, b, a);
        touch(LegacyGroups.CURRENT);
    }

    public static void color(float r, float g, float b) {
        pullLegacy();
        color(r, g, b, 1f);
    }

    /** The current colour packed like the Tessellator (R in the lowest byte); channels are rounded and clamped like GL's float to ubyte. */
    public static int colorAbgr() {
        return colAbgr;
    }

    public static float colorR() {
        return colR;
    }

    public static float colorG() {
        return colG;
    }

    public static float colorB() {
        return colB;
    }

    public static float colorA() {
        return colA;
    }

    public static void normal(float x, float y, float z) {
        pullLegacy();
        nrmX = x;
        nrmY = y;
        nrmZ = z;
        touch(LegacyGroups.CURRENT);
    }

    public static float normalX() {
        return nrmX;
    }

    public static float normalY() {
        return nrmY;
    }

    public static float normalZ() {
        return nrmZ;
    }

    /** {@code OpenGlHelper.setLightmapTextureCoords(lightmapTexUnit, u, v)}: block light u, sky light v (0..240 range of MITE). */
    public static void light(float blockU, float skyV) {
        pullLegacy();
        lightU = blockU;
        lightV = skyV;
        lightPacked = packLight(blockU, skyV);
        touch(LegacyGroups.CURRENT);
    }

    public static float lightU() {
        return lightU;
    }

    public static float lightV() {
        return lightV;
    }

    /** The current lightmap coordinate as the Tessellator brightness int: low short = u, high short = v. */
    public static int lightPacked() {
        return lightPacked;
    }

    /** Overlay colour and mix factor (alpha) of the following vertices; {@code a <= 0} means no overlay. */
    public static void overlay(float r, float g, float b, float a) {
        pullLegacy();
        ovR = r;
        ovG = g;
        ovB = b;
        ovA = a;
        ovArgb = a > 0f ? argb(r, g, b, a) : 0;
        keyValid = false;
    }

    public static void overlayNone() {
        pullLegacy();
        ovR = ovG = ovB = ovA = 0f;
        ovArgb = 0;
        keyValid = false;
    }

    public static boolean overlayActive() {
        return ovA > 0f;
    }

    /** The overlay as packed ARGB (alpha = mix factor); 0 when none. */
    public static int overlayArgb() {
        return ovArgb;
    }

    /** Overlay rgb as packed ABGR with the mix factor in the alpha byte (the layout {@link Lighting#lit} takes). */
    public static int overlayAbgr() {
        return packUnit(ovR, ovG, ovB, ovA);
    }

    // ================================================================================================ texture units

    /** 0 = colour texture, 1 = lightmap. */
    public static void activeUnit(int unit) {
        pullLegacy();
        if (unit < 0 || unit > 1) {
            throw new IllegalArgumentException("texture unit " + unit);
        }
        if (activeUnit != unit) {
            activeUnit = unit;
            touch(LegacyGroups.UNIT);
        }
    }

    public static int activeUnit() {
        return activeUnit;
    }

    /** Binds on the active unit ({@code Textures.bind} calls this). */
    public static void bindTexture(int textureId) {
        pullLegacy();
        if (boundTexture[activeUnit] != textureId) {
            boundTexture[activeUnit] = textureId;
            touch(LegacyGroups.TEXTURE_BIND);
        }
    }

    public static int boundTexture(int unit) {
        return boundTexture[unit];
    }

    /** GL_TEXTURE_2D of the active unit (unit 1 = lightmap enable). */
    public static void texturing(boolean on) {
        pullLegacy();
        if (texturing[activeUnit] != on) {
            texturing[activeUnit] = on;
            keyValid = false;
            touch(LegacyGroups.TEXTURE_ENABLE);
        }
    }

    /** Of unit 0. */
    public static boolean texturingEnabled() {
        return texturing[0];
    }

    public static boolean lightmapEnabled() {
        return texturing[1];
    }

    /** Texture bound on unit 1. */
    public static int lightmapTexture() {
        return boundTexture[1];
    }

    // ================================================================================================ blend / alpha / depth / cull

    public static void blend(boolean on) {
        pullLegacy();
        if (blend != on) {
            blend = on;
            keyValid = false;
            touch(LegacyGroups.BLEND);
        }
    }

    public static boolean blendEnabled() {
        return blend;
    }

    public static void blendFunc(BlendFactor src, BlendFactor dst) {
        pullLegacy();
        if (blendSrc != src || blendDst != dst) {
            blendSrc = src;
            blendDst = dst;
            keyValid = false;
            touch(LegacyGroups.BLEND);
        }
    }

    public static BlendFactor blendSrc() {
        return blendSrc;
    }

    public static BlendFactor blendDst() {
        return blendDst;
    }

    public static void alphaTest(boolean on) {
        pullLegacy();
        if (alphaTest != on) {
            alphaTest = on;
            keyValid = false;
            touch(LegacyGroups.ALPHA);
        }
    }

    public static boolean alphaTestEnabled() {
        return alphaTest;
    }

    /** glAlphaFunc(GL_GREATER, ref): the only function MITE uses. */
    public static void alphaFunc(float greaterThan) {
        pullLegacy();
        if (Float.floatToIntBits(alphaRef) != Float.floatToIntBits(greaterThan)) {
            alphaRef = greaterThan;
            keyValid = false;
            touch(LegacyGroups.ALPHA);
        }
    }

    public static float alphaRef() {
        return alphaRef;
    }

    public static void depthTest(boolean on) {
        pullLegacy();
        if (depthTest != on) {
            depthTest = on;
            keyValid = false;
            touch(LegacyGroups.DEPTH);
        }
    }

    public static boolean depthTestEnabled() {
        return depthTest;
    }

    public static void depthFunc(DepthFunc f) {
        pullLegacy();
        if (depthFunc != f) {
            depthFunc = f;
            keyValid = false;
            touch(LegacyGroups.DEPTH);
        }
    }

    public static DepthFunc depthFuncState() {
        return depthFunc;
    }

    public static void depthMask(boolean write) {
        pullLegacy();
        if (depthMask != write) {
            depthMask = write;
            keyValid = false;
            touch(LegacyGroups.DEPTH);
        }
    }

    public static boolean depthMaskEnabled() {
        return depthMask;
    }

    public static void cull(boolean on) {
        pullLegacy();
        if (cull != on) {
            cull = on;
            keyValid = false;
            touch(LegacyGroups.CULL);
        }
    }

    public static boolean cullEnabled() {
        return cull;
    }

    public static void cullFace(Face face) {
        pullLegacy();
        if (cullFace != face) {
            cullFace = face;
            keyValid = false;
            touch(LegacyGroups.CULL);
        }
    }

    public static Face cullFaceState() {
        return cullFace;
    }

    /** Alpha is accepted and ignored (1.3: colour writes never include alpha). */
    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        pullLegacy();
        int m = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0);
        if (colorMask != m) {
            colorMask = m;
            keyValid = false;
            touch(LegacyGroups.COLOR_MASK);
        }
    }

    /** Bits 1 = R, 2 = G, 4 = B. */
    public static int colorMaskBits() {
        return colorMask;
    }

    public static void polygonOffsetFill(boolean on) {
        pullLegacy();
        if (polyOffset != on) {
            polyOffset = on;
            keyValid = false;
            touch(LegacyGroups.POLYGON_OFFSET);
        }
    }

    public static boolean polygonOffsetEnabled() {
        return polyOffset;
    }

    /** Dynamic state (not part of the material key). */
    public static void polygonOffset(float factor, float units) {
        pullLegacy();
        if (polyFactor != factor || polyUnits != units) {
            polyFactor = factor;
            polyUnits = units;
            touch(LegacyGroups.POLYGON_OFFSET);
        }
    }

    public static float polygonOffsetFactor() {
        return polyFactor;
    }

    public static float polygonOffsetUnits() {
        return polyUnits;
    }

    /** Dynamic state; the width is in framebuffer pixels. */
    public static void lineWidth(float px) {
        pullLegacy();
        if (lineWidth != px) {
            lineWidth = px;
            touch(LegacyGroups.LINE);
        }
    }

    public static float lineWidthPx() {
        return lineWidth;
    }

    public static void logicOpCursor(boolean on) {
        pullLegacy();
        if (logicOp != on) {
            logicOp = on;
            keyValid = false;
            touch(LegacyGroups.LOGIC);
        }
    }

    public static boolean logicOpCursorEnabled() {
        return logicOp;
    }

    /** glShadeModel: recorded only (1.9, no flat pipelines exist). */
    public static void smoothShading(boolean on) {
        pullLegacy();
        if (smooth != on) {
            smooth = on;
            touch(LegacyGroups.SHADE);
        }
    }

    public static boolean smoothShadingEnabled() {
        return smooth;
    }

    // ================================================================================================ fog

    public static void fog(boolean on) {
        pullLegacy();
        if (fog != on) {
            fog = on;
            keyValid = false;
            fogVersion++;
            touch(LegacyGroups.FOG);
        }
    }

    public static boolean fogEnabled() {
        return fog;
    }

    public static void fogMode(FogMode m) {
        pullLegacy();
        if (fogMode != m) {
            fogMode = m;
            fogVersion++;
            touch(LegacyGroups.FOG);
        }
    }

    public static FogMode fogModeState() {
        return fogMode;
    }

    public static void fogStart(float v) {
        pullLegacy();
        if (fogStart != v) {
            fogStart = v;
            fogVersion++;
            touch(LegacyGroups.FOG);
        }
    }

    public static float fogStartValue() {
        return fogStart;
    }

    public static void fogEnd(float v) {
        pullLegacy();
        if (fogEnd != v) {
            fogEnd = v;
            fogVersion++;
            touch(LegacyGroups.FOG);
        }
    }

    public static float fogEndValue() {
        return fogEnd;
    }

    public static void fogDensity(float v) {
        pullLegacy();
        if (fogDensity != v) {
            fogDensity = v;
            fogVersion++;
            touch(LegacyGroups.FOG);
        }
    }

    public static float fogDensityValue() {
        return fogDensity;
    }

    public static void fogColor(float r, float g, float b, float a) {
        pullLegacy();
        if (fogColor[0] != r || fogColor[1] != g || fogColor[2] != b || fogColor[3] != a) {
            fogColor[0] = r;
            fogColor[1] = g;
            fogColor[2] = b;
            fogColor[3] = a;
            fogVersion++;
            touch(LegacyGroups.FOG);
        }
    }

    /** Component 0..3 (r, g, b, a) of the fog colour. */
    public static float fogColorComponent(int i) {
        return fogColor[i];
    }

    /** Takes a new value on every change of the fog state: a flush trigger of the batching layer (1.8). */
    public static int fogVersion() {
        return fogVersion;
    }

    // ================================================================================================ lighting

    public static void lighting(boolean on) {
        pullLegacy();
        if (lighting != on) {
            lighting = on;
            touchLighting();
        }
    }

    public static boolean lightingEnabled() {
        return lighting;
    }

    public static void rescaleNormal(boolean on) {
        pullLegacy();
        if (rescale != on) {
            rescale = on;
            touch(LegacyGroups.NORMAL_MODE);
        }
    }

    public static boolean rescaleNormalEnabled() {
        return rescale;
    }

    public static void normalize(boolean on) {
        pullLegacy();
        if (normalize != on) {
            normalize = on;
            touch(LegacyGroups.NORMAL_MODE);
        }
    }

    public static boolean normalizeEnabled() {
        return normalize;
    }

    public static void colorMaterialEnabled(boolean on) {
        pullLegacy();
        if (colorMaterial != on) {
            colorMaterial = on;
            touchLighting();
        }
    }

    public static boolean colorMaterialEnabledState() {
        return colorMaterial;
    }

    public static void colorMaterial(ColorMaterial mode) {
        pullLegacy();
        if (colorMaterialMode != mode) {
            colorMaterialMode = mode;
            touchLighting();
        }
    }

    public static ColorMaterial colorMaterialMode() {
        return colorMaterialMode;
    }

    // ================================================================================================ attribute stack

    /** glPushAttrib with {@link AttribMask} bits. The VIEWPORT group is owned by {@code Frame} and not saved here. */
    public static void pushAttrib(int attribBits) {
        pullLegacy();
        if (attribTop >= ATTRIB_DEPTH) {
            if (!attribWarned) {
                attribWarned = true;
                VkLog.LOGGER.warn("Draw attribute stack overflow (depth {}); further pushes are ignored", ATTRIB_DEPTH);
            }
            attribTop++; // keep push/pop balanced for the ignored levels
            return;
        }
        Snapshot s = ATTRIBS[attribTop++];
        s.mask = attribBits;
        s.save();
    }

    public static void popAttrib() {
        pullLegacy();
        if (attribTop == 0) {
            if (!attribWarned) {
                attribWarned = true;
                VkLog.LOGGER.warn("Draw attribute stack underflow; ignored");
            }
            return;
        }
        attribTop--;
        if (attribTop >= ATTRIB_DEPTH) {
            return;
        }
        ATTRIBS[attribTop].restore();
    }

    public static int attribDepth() {
        return attribTop;
    }

    /** Saved copy of the state groups selected by {@code mask}. */
    private static final class Snapshot {
        int mask;
        float cr, cg, cb, ca, nx, ny, nz, lu, lv;
        boolean blend, alphaTest, depthTest, depthMask, cull, polyOffset, logicOp, fog, lighting, rescale, normalize, colorMaterial, light0, light1, smooth;
        boolean tex0, tex1;
        BlendFactor src, dst;
        float alphaRef, polyFactor, polyUnits, lineWidth, fogStart, fogEnd, fogDensity;
        DepthFunc depthFunc;
        Face face;
        int colorMask, bound0, bound1;
        FogMode fogMode;
        ColorMaterial cmMode;
        float modelAmbient;
        final float[] fogColor = new float[4];
        final float[] dir0 = new float[3], dir1 = new float[3];

        void save() {
            cr = colR;
            cg = colG;
            cb = colB;
            ca = colA;
            nx = nrmX;
            ny = nrmY;
            nz = nrmZ;
            lu = lightU;
            lv = lightV;
            blend = Draw.blend;
            alphaTest = Draw.alphaTest;
            depthTest = Draw.depthTest;
            depthMask = Draw.depthMask;
            cull = Draw.cull;
            polyOffset = Draw.polyOffset;
            logicOp = Draw.logicOp;
            fog = Draw.fog;
            lighting = Draw.lighting;
            rescale = Draw.rescale;
            normalize = Draw.normalize;
            colorMaterial = Draw.colorMaterial;
            light0 = Draw.light0;
            light1 = Draw.light1;
            smooth = Draw.smooth;
            tex0 = texturing[0];
            tex1 = texturing[1];
            src = blendSrc;
            dst = blendDst;
            alphaRef = Draw.alphaRef;
            polyFactor = Draw.polyFactor;
            polyUnits = Draw.polyUnits;
            lineWidth = Draw.lineWidth;
            fogStart = Draw.fogStart;
            fogEnd = Draw.fogEnd;
            fogDensity = Draw.fogDensity;
            depthFunc = Draw.depthFunc;
            face = cullFace;
            colorMask = Draw.colorMask;
            bound0 = boundTexture[0];
            bound1 = boundTexture[1];
            fogMode = Draw.fogMode;
            cmMode = colorMaterialMode;
            modelAmbient = Lighting.modelAmbient();
            System.arraycopy(Draw.fogColor, 0, fogColor, 0, 4);
            System.arraycopy(LIGHT_DIR0, 0, dir0, 0, 3);
            System.arraycopy(LIGHT_DIR1, 0, dir1, 0, 3);
        }

        void restore() {
            final int m = mask;
            if ((m & AttribMask.CURRENT) != 0) {
                color(cr, cg, cb, ca);
                normal(nx, ny, nz);
                light(lu, lv);
            }
            if ((m & (AttribMask.ENABLE | AttribMask.COLOR_BUFFER)) != 0) {
                blend(blend);
                alphaTest(alphaTest);
                logicOpCursor(logicOp);
            }
            if ((m & AttribMask.COLOR_BUFFER) != 0) {
                blendFunc(src, dst);
                alphaFunc(alphaRef);
                colorMask((colorMask & 1) != 0, (colorMask & 2) != 0, (colorMask & 4) != 0, true);
            }
            if ((m & (AttribMask.ENABLE | AttribMask.DEPTH)) != 0) {
                depthTest(depthTest);
            }
            if ((m & AttribMask.DEPTH) != 0) {
                depthFunc(depthFunc);
                depthMask(depthMask);
            }
            if ((m & (AttribMask.ENABLE | AttribMask.POLYGON)) != 0) {
                cull(cull);
                polygonOffsetFill(polyOffset);
            }
            if ((m & AttribMask.POLYGON) != 0) {
                cullFace(face);
                polygonOffset(polyFactor, polyUnits);
            }
            if ((m & (AttribMask.ENABLE | AttribMask.FOG)) != 0) {
                fog(fog);
            }
            if ((m & AttribMask.FOG) != 0) {
                fogMode(fogMode);
                fogStart(fogStart);
                fogEnd(fogEnd);
                fogDensity(fogDensity);
                fogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            }
            if ((m & AttribMask.LINE) != 0) {
                lineWidth(lineWidth);
            }
            if ((m & (AttribMask.ENABLE | AttribMask.LIGHTING)) != 0) {
                Draw.lighting(lighting);
                rescaleNormal(rescale);
                normalize(normalize);
                if (Draw.colorMaterial != colorMaterial) {
                    Draw.colorMaterial = colorMaterial;
                    touchLighting();
                }
                if (Draw.light0 != light0 || Draw.light1 != light1) {
                    Draw.light0 = light0;
                    Draw.light1 = light1;
                    touchLighting();
                }
            }
            if ((m & AttribMask.LIGHTING) != 0) {
                colorMaterial(cmMode);
                smoothShading(smooth);
                System.arraycopy(dir0, 0, LIGHT_DIR0, 0, 3);
                System.arraycopy(dir1, 0, LIGHT_DIR1, 0, 3);
                Lighting.setModelAmbient(modelAmbient);
                touchLighting();
            }
            if ((m & (AttribMask.ENABLE | AttribMask.TEXTURE)) != 0) {
                int unit = activeUnit;
                activeUnit = 0;
                texturing(tex0);
                activeUnit = 1;
                texturing(tex1);
                activeUnit = unit;
            }
            if ((m & AttribMask.TEXTURE) != 0) {
                int unit = activeUnit;
                activeUnit = 0;
                bindTexture(bound0);
                activeUnit = 1;
                bindTexture(bound1);
                activeUnit = unit;
            }
        }
    }

    // ================================================================================================ material key / render type

    /** The key of the current state for program {@link Program#STREAM} (1.7 layout); the legacy state is pulled first when it is dirty. */
    public static long materialKey() {
        pullLegacy();
        if (!keyValid) {
            cachedKey = buildKey(Program.STREAM, VertexFormat.UNIVERSAL);
            keyValid = true;
        }
        return cachedKey;
    }

    private static long buildKey(Program program, VertexFormat format) {
        boolean lines = format == VertexFormat.LINE;
        int alphaMode = 0;
        int alphaIndex = 0;
        if (alphaTest) {
            int idx = AlphaRefs.index(alphaRef);
            if (idx >= 0) {
                alphaMode = 1;
                alphaIndex = idx;
            } else {
                alphaMode = 2;
            }
        }
        boolean depthOn = depthTest;
        boolean blendOn = blend;
        return MaterialKey.pack(program.ordinal(), texturing[0] && !lines, texturing[1] && !lines, fog, ovA > 0f && !lines, alphaMode, alphaIndex,
                blendOn, blendOn ? blendSrc.ordinal() : BlendFactor.ONE.ordinal(), blendOn ? blendDst.ordinal() : BlendFactor.ZERO.ordinal(),
                depthOn, depthOn ? depthFunc.ordinal() : DepthFunc.ALWAYS.ordinal(), depthOn && depthMask,
                cull && cullFace == Face.BACK, colorMask, polyOffset, logicOp, cull && cullFace == Face.FRONT);
    }

    /** The render type of the current state for {@code format}: program by format, textures bound on unit 0 / 1 (0 when not enabled). */
    public static RenderType currentRenderType(VertexFormat format) {
        long key;
        if (format == VertexFormat.UNIVERSAL) {
            key = materialKey();
        } else {
            pullLegacy();
            Program p = switch (format) {
                case TERRAIN -> Program.TERRAIN;
                case LINE -> Program.LINES;
                case PORTAL -> Program.PORTAL;
                default -> Program.STREAM;
            };
            key = buildKey(p, format);
        }
        boolean tex = MaterialKey.textured(key);
        boolean lm = MaterialKey.lightmap(key);
        return RenderType.of(key, format, tex ? boundTexture[0] : 0, lm ? boundTexture[1] : 0);
    }

    // ================================================================================================ static meshes

    /** Registers the receiver of {@link #drawStatic} (the batching layer, NR-03). */
    public static void setStaticDrawHandler(StaticDrawHandler handler) {
        staticHandler = handler;
    }

    /**
     * Enqueues {@code mesh} as an ordered entry with the current pose as model matrix and the current colour as colour modulator (1.6).
     * The render type is the current state with program STATIC.
     */
    public static void drawStatic(StaticMesh mesh) {
        StaticDrawHandler h = staticHandler;
        if (h == null) {
            throw new UnsupportedOperationException("NR-03");
        }
        pullLegacy();
        long key = buildKey(Program.STATIC, VertexFormat.UNIVERSAL);
        boolean tex = MaterialKey.textured(key);
        boolean lm = MaterialKey.lightmap(key);
        h.drawStatic(mesh, RenderType.of(key, VertexFormat.UNIVERSAL, tex ? boundTexture[0] : 0, lm ? boundTexture[1] : 0), POSE.top(), colAbgr);
    }

    // ================================================================================================ reset

    /** GL default state; used by {@code Minecraft.startGame} and tests. Matrices become identity, the attribute stack is emptied. */
    public static void resetToGlDefaults() {
        pullLegacy();
        POSE.reset();
        PROJECTION.reset();
        UV.reset();
        GlShim.resetMode();
        colR = colG = colB = colA = 1f;
        colAbgr = 0xFFFFFFFF;
        nrmX = 0f;
        nrmY = 0f;
        nrmZ = 1f;
        lightU = lightV = 0f;
        lightPacked = 0;
        ovR = ovG = ovB = ovA = 0f;
        ovArgb = 0;
        activeUnit = 0;
        boundTexture[0] = boundTexture[1] = 0;
        texturing[0] = texturing[1] = false;
        blend = false;
        blendSrc = BlendFactor.ONE;
        blendDst = BlendFactor.ZERO;
        alphaTest = false;
        alphaRef = 0f;
        depthTest = false;
        depthFunc = DepthFunc.LESS;
        depthMask = true;
        cull = false;
        cullFace = Face.BACK;
        colorMask = 7;
        polyOffset = false;
        polyFactor = polyUnits = 0f;
        lineWidth = 1f;
        logicOp = false;
        smooth = true;
        fog = false;
        fogMode = FogMode.EXP;
        fogStart = 0f;
        fogEnd = 1f;
        fogDensity = 1f;
        fogColor[0] = fogColor[1] = fogColor[2] = fogColor[3] = 0f;
        fogVersion++;
        lighting = false;
        rescale = false;
        normalize = false;
        colorMaterial = false;
        colorMaterialMode = ColorMaterial.AMBIENT_AND_DIFFUSE;
        light0 = light1 = false;
        LIGHT_DIR0[0] = LIGHT_DIR0[1] = LIGHT_DIR0[2] = 0f;
        LIGHT_DIR1[0] = LIGHT_DIR1[1] = LIGHT_DIR1[2] = 0f;
        Lighting.reset();
        attribTop = 0;
        keyValid = false;
        dirtyLegacy = LegacyGroups.ALL;
        StateMirror.onNativeChanged(LegacyGroups.ALL);
    }

    // ================================================================================================ packing helpers

    /** float 0..1 to ubyte like GL (round to nearest, clamped). */
    static int unitToByte(float v) {
        int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : Math.min(i, 255);
    }

    static int packUnit(float r, float g, float b, float a) {
        return unitToByte(a) << 24 | unitToByte(b) << 16 | unitToByte(g) << 8 | unitToByte(r);
    }

    private static int argb(float r, float g, float b, float a) {
        return unitToByte(a) << 24 | unitToByte(r) << 16 | unitToByte(g) << 8 | unitToByte(b);
    }

    private static int packLight(float u, float v) {
        int iu = Math.round(u);
        int iv = Math.round(v);
        iu = iu < 0 ? 0 : Math.min(iu, 0xFFFF);
        iv = iv < 0 ? 0 : Math.min(iv, 0xFFFF);
        return iv << 16 | iu;
    }
}
