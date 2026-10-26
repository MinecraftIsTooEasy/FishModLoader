package net.xiaoyu233.fml.vulkan.gl.state;

import net.xiaoyu233.fml.vulkan.texture.TextureManager;

import static net.xiaoyu233.fml.vulkan.gl.state.GLConst.*;

/**
 * The glPushAttrib / glPopAttrib stack (16 entries, preallocated: pushing and popping never allocates). Every attribute group
 * saves the GL <i>source</i> state of its bit (enums, flags, values); the derived pipeline bits and uniform data are rebuilt
 * lazily by {@link GLState} after a pop. The capability flags that belong to a group (blend, depth test, cull face, lighting,
 * fog, scissor test, ...) are saved with the group, GL_ENABLE_BIT saves all of them.
 */
final class AttribStack {
    private static final int UNIFORM_GROUPS = GL_LIGHTING_BIT | GL_FOG_BIT | GL_TRANSFORM_BIT | GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT
            | GL_TEXTURE_BIT;

    private static final class Entry {
        int mask;
        long caps;
        long capMask;

        // GL_CURRENT_BIT
        final float[] color = new float[4];
        final float[] normal = new float[3];
        final float[][] texCoord = new float[GLState.MAX_TEXTURE_UNITS][4];
        // GL_POINT_BIT / GL_LINE_BIT
        float pointSize, lineWidth;
        // GL_POLYGON_BIT
        int cullFaceMode, frontFace, polyModeFront, polyModeBack;
        float polyOffsetFactor, polyOffsetUnits;
        // GL_LIGHTING_BIT
        final LightState[] lights = new LightState[GLState.MAX_LIGHTS];
        final float[] lightModelAmbient = new float[4];
        boolean lightModelLocalViewer, lightModelTwoSide;
        int lightModelColorControl;
        final MaterialState material = new MaterialState();
        final MaterialState materialBack = new MaterialState();
        int colorMaterialFace, colorMaterialMode, shadeModel;
        // GL_FOG_BIT
        final FogState fog = new FogState();
        // GL_DEPTH_BUFFER_BIT
        int depthFunc;
        boolean depthMask;
        float clearDepth;
        // GL_STENCIL_BUFFER_BIT
        int stencilFunc, stencilRef, stencilValueMask, stencilWriteMask, stencilFail, stencilZFail, stencilZPass, clearStencil;
        // GL_VIEWPORT_BIT
        final int[] viewport = new int[4];
        double depthNear, depthFar;
        // GL_TRANSFORM_BIT
        int matrixMode;
        // GL_ENABLE_BIT (the capability bits themselves are in caps/capMask)
        final int[] targetEnable = new int[GLState.MAX_TEXTURE_UNITS];
        final int[] texGenEnable = new int[GLState.MAX_TEXTURE_UNITS];
        // GL_COLOR_BUFFER_BIT
        int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha, blendEqRgb, blendEqAlpha;
        final float[] blendColor = new float[4];
        int logicOp, colorMask, drawBuffer, alphaFunc;
        final float[] clearColor = new float[4];
        float alphaRef;
        // GL_HINT_BIT
        final int[] hints = new int[7];
        // GL_TEXTURE_BIT (the active texture unit selector belongs to this group, like the bindings of each unit)
        final TexUnitState[] texUnits = new TexUnitState[GLState.MAX_TEXTURE_UNITS];
        Object textureBindings;
        int activeTexture;
        // GL_SCISSOR_BIT
        final int[] scissorBox = new int[4];

        Entry() {
            for (int i = 0; i < lights.length; i++) {
                lights[i] = new LightState();
            }
            for (int i = 0; i < texUnits.length; i++) {
                texUnits[i] = new TexUnitState();
            }
        }
    }

    private final Entry[] entries = new Entry[GLState.ATTRIB_STACK_DEPTH];
    private int depth;

    AttribStack() {
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new Entry();
        }
    }

    void reset() {
        for (int i = 0; i < depth; i++) {
            entries[i].textureBindings = null;
        }
        depth = 0;
    }

    /** Number of saved entries (GL_ATTRIB_STACK_DEPTH). */
    int depth() {
        return depth;
    }

    /** Returns false on overflow (the caller raises GL_STACK_OVERFLOW). */
    boolean push(GLState s, int mask) {
        if (depth >= entries.length) {
            return false;
        }
        final Entry e = entries[depth++];
        e.mask = mask;
        e.caps = s.caps;
        e.capMask = 0;
        e.textureBindings = null;

        if ((mask & GL_CURRENT_BIT) != 0) {
            System.arraycopy(s.currentColor, 0, e.color, 0, 4);
            System.arraycopy(s.currentNormal, 0, e.normal, 0, 3);
            for (int i = 0; i < GLState.MAX_TEXTURE_UNITS; i++) {
                System.arraycopy(s.currentTexCoord[i], 0, e.texCoord[i], 0, 4);
            }
        }
        if ((mask & GL_POINT_BIT) != 0) {
            e.pointSize = s.pointSize;
            e.capMask |= GLState.CAPS_POINT;
        }
        if ((mask & GL_LINE_BIT) != 0) {
            e.lineWidth = s.lineWidth;
            e.capMask |= GLState.CAPS_LINE;
        }
        if ((mask & GL_POLYGON_BIT) != 0) {
            e.cullFaceMode = s.cullFaceMode;
            e.frontFace = s.frontFace;
            e.polyModeFront = s.polyModeFront;
            e.polyModeBack = s.polyModeBack;
            e.polyOffsetFactor = s.polyOffsetFactor;
            e.polyOffsetUnits = s.polyOffsetUnits;
            e.capMask |= GLState.CAPS_POLYGON;
        }
        if ((mask & GL_LIGHTING_BIT) != 0) {
            for (int i = 0; i < GLState.MAX_LIGHTS; i++) {
                e.lights[i].copyFrom(s.lights[i]);
            }
            System.arraycopy(s.lightModelAmbient, 0, e.lightModelAmbient, 0, 4);
            e.lightModelLocalViewer = s.lightModelLocalViewer;
            e.lightModelTwoSide = s.lightModelTwoSide;
            e.lightModelColorControl = s.lightModelColorControl;
            e.material.copyFrom(s.material);
            e.materialBack.copyFrom(s.materialBack);
            e.colorMaterialFace = s.colorMaterialFace;
            e.colorMaterialMode = s.colorMaterialMode;
            e.shadeModel = s.shadeModel;
            e.capMask |= GLState.CAPS_LIGHTING;
        }
        if ((mask & GL_FOG_BIT) != 0) {
            e.fog.copyFrom(s.fog);
            e.capMask |= GLState.CAPS_FOG;
        }
        if ((mask & GL_DEPTH_BUFFER_BIT) != 0) {
            e.depthFunc = s.depthFunc;
            e.depthMask = s.depthMask;
            e.clearDepth = s.clearDepth;
            e.capMask |= GLState.CAPS_DEPTH;
        }
        if ((mask & GL_STENCIL_BUFFER_BIT) != 0) {
            e.stencilFunc = s.stencilFunc;
            e.stencilRef = s.stencilRef;
            e.stencilValueMask = s.stencilValueMask;
            e.stencilWriteMask = s.stencilWriteMask;
            e.stencilFail = s.stencilFail;
            e.stencilZFail = s.stencilZFail;
            e.stencilZPass = s.stencilZPass;
            e.clearStencil = s.clearStencil;
            e.capMask |= GLState.CAPS_STENCIL;
        }
        if ((mask & GL_VIEWPORT_BIT) != 0) {
            System.arraycopy(s.viewport, 0, e.viewport, 0, 4);
            e.depthNear = s.depthNear;
            e.depthFar = s.depthFar;
        }
        if ((mask & GL_TRANSFORM_BIT) != 0) {
            e.matrixMode = s.matrixMode;
            e.capMask |= GLState.CAPS_TRANSFORM;
        }
        if ((mask & GL_ENABLE_BIT) != 0) {
            e.capMask |= GLState.CAPS_ALL;
            for (int i = 0; i < GLState.MAX_TEXTURE_UNITS; i++) {
                e.targetEnable[i] = s.texUnits[i].targetEnable;
                e.texGenEnable[i] = s.texUnits[i].texGenEnable;
            }
        }
        if ((mask & GL_COLOR_BUFFER_BIT) != 0) {
            e.blendSrcRgb = s.blendSrcRgb;
            e.blendDstRgb = s.blendDstRgb;
            e.blendSrcAlpha = s.blendSrcAlpha;
            e.blendDstAlpha = s.blendDstAlpha;
            e.blendEqRgb = s.blendEqRgb;
            e.blendEqAlpha = s.blendEqAlpha;
            System.arraycopy(s.blendColor, 0, e.blendColor, 0, 4);
            e.logicOp = s.logicOp;
            e.colorMask = s.colorMask;
            e.drawBuffer = s.drawBuffer;
            e.alphaFunc = s.alphaFunc;
            e.alphaRef = s.alphaRef;
            System.arraycopy(s.clearColor, 0, e.clearColor, 0, 4);
            e.capMask |= GLState.CAPS_COLOR_BUFFER;
        }
        if ((mask & GL_HINT_BIT) != 0) {
            System.arraycopy(s.hints, 0, e.hints, 0, e.hints.length);
        }
        if ((mask & GL_TEXTURE_BIT) != 0) {
            for (int i = 0; i < GLState.MAX_TEXTURE_UNITS; i++) {
                e.texUnits[i].copyFrom(s.texUnits[i]);
            }
            e.textureBindings = TextureManager.saveBindings();
            e.activeTexture = s.activeTextureUnit;
        }
        if ((mask & GL_SCISSOR_BIT) != 0) {
            System.arraycopy(s.scissorBox, 0, e.scissorBox, 0, 4);
            e.capMask |= GLState.CAPS_SCISSOR;
        }
        return true;
    }

    /** Returns false on underflow (the caller raises GL_STACK_UNDERFLOW). */
    boolean pop(GLState s) {
        if (depth == 0) {
            return false;
        }
        final Entry e = entries[--depth];
        final int mask = e.mask;

        if (e.capMask != 0) {
            s.caps = (s.caps & ~e.capMask) | (e.caps & e.capMask);
        }
        if ((mask & GL_CURRENT_BIT) != 0) {
            System.arraycopy(e.color, 0, s.currentColor, 0, 4);
            System.arraycopy(e.normal, 0, s.currentNormal, 0, 3);
            for (int i = 0; i < GLState.MAX_TEXTURE_UNITS; i++) {
                System.arraycopy(e.texCoord[i], 0, s.currentTexCoord[i], 0, 4);
            }
        }
        if ((mask & GL_POINT_BIT) != 0) {
            s.pointSize = e.pointSize;
        }
        if ((mask & GL_LINE_BIT) != 0) {
            s.lineWidth = e.lineWidth;
        }
        if ((mask & GL_POLYGON_BIT) != 0) {
            s.cullFaceMode = e.cullFaceMode;
            s.frontFace = e.frontFace;
            s.polyModeFront = e.polyModeFront;
            s.polyModeBack = e.polyModeBack;
            s.polyOffsetFactor = e.polyOffsetFactor;
            s.polyOffsetUnits = e.polyOffsetUnits;
        }
        if ((mask & GL_LIGHTING_BIT) != 0) {
            for (int i = 0; i < GLState.MAX_LIGHTS; i++) {
                s.lights[i].copyFrom(e.lights[i]);
            }
            System.arraycopy(e.lightModelAmbient, 0, s.lightModelAmbient, 0, 4);
            s.lightModelLocalViewer = e.lightModelLocalViewer;
            s.lightModelTwoSide = e.lightModelTwoSide;
            s.lightModelColorControl = e.lightModelColorControl;
            s.material.copyFrom(e.material);
            s.materialBack.copyFrom(e.materialBack);
            s.colorMaterialFace = e.colorMaterialFace;
            s.colorMaterialMode = e.colorMaterialMode;
            s.shadeModel = e.shadeModel;
        }
        if ((mask & GL_FOG_BIT) != 0) {
            s.fog.copyFrom(e.fog);
        }
        if ((mask & GL_DEPTH_BUFFER_BIT) != 0) {
            s.depthFunc = e.depthFunc;
            s.depthMask = e.depthMask;
            s.clearDepth = e.clearDepth;
        }
        if ((mask & GL_STENCIL_BUFFER_BIT) != 0) {
            s.stencilFunc = e.stencilFunc;
            s.stencilRef = e.stencilRef;
            s.stencilValueMask = e.stencilValueMask;
            s.stencilWriteMask = e.stencilWriteMask;
            s.stencilFail = e.stencilFail;
            s.stencilZFail = e.stencilZFail;
            s.stencilZPass = e.stencilZPass;
            s.clearStencil = e.clearStencil;
        }
        if ((mask & GL_VIEWPORT_BIT) != 0) {
            System.arraycopy(e.viewport, 0, s.viewport, 0, 4);
            s.depthNear = e.depthNear;
            s.depthFar = e.depthFar;
        }
        if ((mask & GL_TRANSFORM_BIT) != 0) {
            s.matrixMode = e.matrixMode;
        }
        if ((mask & GL_ENABLE_BIT) != 0) {
            for (int i = 0; i < GLState.MAX_TEXTURE_UNITS; i++) {
                s.texUnits[i].targetEnable = e.targetEnable[i];
                s.texUnits[i].texGenEnable = e.texGenEnable[i];
            }
        }
        if ((mask & GL_COLOR_BUFFER_BIT) != 0) {
            s.blendSrcRgb = e.blendSrcRgb;
            s.blendDstRgb = e.blendDstRgb;
            s.blendSrcAlpha = e.blendSrcAlpha;
            s.blendDstAlpha = e.blendDstAlpha;
            s.blendEqRgb = e.blendEqRgb;
            s.blendEqAlpha = e.blendEqAlpha;
            System.arraycopy(e.blendColor, 0, s.blendColor, 0, 4);
            s.logicOp = e.logicOp;
            s.colorMask = e.colorMask;
            s.drawBuffer = e.drawBuffer;
            s.alphaFunc = e.alphaFunc;
            s.alphaRef = e.alphaRef;
            System.arraycopy(e.clearColor, 0, s.clearColor, 0, 4);
        }
        if ((mask & GL_HINT_BIT) != 0) {
            System.arraycopy(e.hints, 0, s.hints, 0, e.hints.length);
        }
        if ((mask & GL_TEXTURE_BIT) != 0) {
            for (int i = 0; i < GLState.MAX_TEXTURE_UNITS; i++) {
                s.texUnits[i].copyFrom(e.texUnits[i]);
            }
            final Object bindings = e.textureBindings;
            e.textureBindings = null;
            if (bindings != null) {
                TextureManager.restoreBindings(bindings);
            }
            // after the bindings, so the unit selector is the saved one whatever the texture side did meanwhile
            s.activeTextureUnit = e.activeTexture;
        }
        if ((mask & GL_SCISSOR_BIT) != 0) {
            System.arraycopy(e.scissorBox, 0, s.scissorBox, 0, 4);
        }
        s.attribRestored((mask & UNIFORM_GROUPS) != 0);
        return true;
    }
}
