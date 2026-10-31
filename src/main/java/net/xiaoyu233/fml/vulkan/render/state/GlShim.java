package net.xiaoyu233.fml.vulkan.render.state;

import java.util.concurrent.atomic.AtomicLong;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import org.lwjgl.opengl.GL11;

/**
 * Template E (3.6a) GL11-signature shim of the native renderer: decodes GL enums (Appendix C) and calls {@link Draw}; render thread only.
 * Every function that has no typed equivalent is forwarded by {@link #unhandled} to the phase-1 GL facade (the safety net) and counted
 * in {@link #fallbackCount()}; this class is the only place of the native renderer that references {@code org.lwjgl.opengl} (the
 * forwarding is the point of the safety net).
 */
public final class GlShim {
    private static final int MODELVIEW = 5888, PROJECTION = 5889, TEXTURE = 5890;

    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static int mode = MODELVIEW;
    private static int lastFilterId = -1;
    private static boolean lastFilterLinear;
    private static int lastWrapId = -1;
    private static boolean lastWrapClamp;
    private static boolean filterPending;
    private static boolean wrapPending;

    private GlShim() {
    }

    /** {@code glMatrixMode} back to MODELVIEW and forget the texture parameter caches; called by {@link Draw#resetToGlDefaults()}. */
    static void resetMode() {
        mode = MODELVIEW;
    }

    /** Number of calls that were forwarded to the phase-1 facade ({@code GLAudit.shimFallbacks()} reads this through NR-08). */
    public static long fallbackCount() {
        return FALLBACKS.get();
    }

    private static final PoseStack SCRATCH = new PoseStack(8);

    private static PoseStack current() {
        if (mode == MODELVIEW) {
            return Draw.pose();
        }
        if (mode == PROJECTION) {
            return Draw.projection();
        }
        // the unit-1 (lightmap) texture matrix is folded into the shader; keep it away from the unit-0 uv matrix
        return Draw.activeUnit() == 1 ? SCRATCH : Draw.uvMatrix();
    }

    // ================================================================================================ enable / disable

    public static void enable(int cap) {
        toggle(cap, true);
    }

    public static void disable(int cap) {
        toggle(cap, false);
    }

    private static void toggle(int cap, boolean on) {
        switch (cap) {
            case 3042 -> Draw.blend(on);
            case 3553 -> Draw.texturing(on);
            case 2896 -> Draw.lighting(on);
            case 3008 -> Draw.alphaTest(on);
            case 32826 -> Draw.rescaleNormal(on);
            case 2977 -> Draw.normalize(on);
            case 2929 -> Draw.depthTest(on);
            case 2884 -> Draw.cull(on);
            case 2912 -> Draw.fog(on);
            case 2903 -> Draw.colorMaterialEnabled(on);
            case 32823 -> Draw.polygonOffsetFill(on);
            case 3058 -> Draw.logicOpCursor(on);
            default -> unhandled(on ? "glEnable" : "glDisable", cap);
        }
    }

    // ================================================================================================ fixed function state

    public static void blendFunc(int src, int dst) {
        BlendFactor s;
        BlendFactor d;
        try {
            s = BlendFactor.of(src);
            d = BlendFactor.of(dst);
        } catch (IllegalArgumentException e) {
            unhandled("glBlendFunc", src, dst);
            return;
        }
        Draw.blendFunc(s, d);
    }

    public static void alphaFunc(int func, float ref) {
        if (func == 516) {
            Draw.alphaFunc(ref);
        } else {
            FALLBACKS.incrementAndGet();
            recordSite("glAlphaFunc");
            if (WARNED.size() < 256) {
                warnOnce("glAlphaFunc(" + func + ")");
            }
            GL11.glAlphaFunc(func, ref);
        }
    }

    public static void depthFunc(int func) {
        DepthFunc f;
        try {
            f = DepthFunc.of(func);
        } catch (IllegalArgumentException e) {
            unhandled("glDepthFunc", func);
            return;
        }
        Draw.depthFunc(f);
    }

    public static void depthMask(boolean flag) {
        Draw.depthMask(flag);
    }

    public static void cullFace(int face) {
        Face f;
        try {
            f = Face.of(face);
        } catch (IllegalArgumentException e) {
            unhandled("glCullFace", face);
            return;
        }
        Draw.cullFace(f);
    }

    public static void shadeModel(int mode) {
        if (mode == 7424) {
            Draw.smoothShading(false);
        } else if (mode == 7425) {
            Draw.smoothShading(true);
        } else {
            unhandled("glShadeModel", mode);
        }
    }

    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        Draw.colorMask(r, g, b, a);
    }

    public static void polygonOffset(float factor, float units) {
        Draw.polygonOffset(factor, units);
    }

    public static void lineWidth(float width) {
        Draw.lineWidth(width);
    }

    // ================================================================================================ texture parameters

    /** MIN/MAG filter and WRAP_S/T of the bound 2D texture; the pairs collapse into one {@code Textures} call. */
    public static void texParameteri(int target, int pname, int param) {
        if (target != 3553) {
            unhandled("glTexParameteri", target, pname, param);
            return;
        }
        switch (pname) {
            case 10240, 10241 -> {
                // 9728 NEAREST and the nearest-mipmap modes are "not linear"; 9729 LINEAR and the linear-mipmap modes are linear
                boolean linear = param == 9729 || param == 9985 || param == 9987;
                setFilter(linear);
            }
            case 10242, 10243 -> setWrap(param != 10497);
            default -> unhandled("glTexParameteri", target, pname, param);
        }
    }

    public static void texParameterf(int target, int pname, float param) {
        if (target != 3553) {
            FALLBACKS.incrementAndGet();
            recordSite("glTexParameterf");
            if (WARNED.size() < 256) {
                warnOnce("glTexParameterf(" + target + ", " + pname + ")");
            }
            GL11.glTexParameterf(target, pname, param);
            return;
        }
        switch (pname) {
            case 10240, 10241, 10242, 10243 -> texParameteri(target, pname, (int) param);
            default -> {
                FALLBACKS.incrementAndGet();
                recordSite("glTexParameterf");
                if (WARNED.size() < 256) {
                    warnOnce("glTexParameterf(" + target + ", " + pname + ")");
                }
                GL11.glTexParameterf(target, pname, param);
            }
        }
    }

    /** Applied on every call: Textures sets MIN and MAG together, so a repeated call is idempotent and no pairing cache can go stale. */
    private static void setFilter(boolean linear) {
        Textures.setFilter(Textures.bound(), linear);
    }

    private static void setWrap(boolean clamp) {
        Textures.setWrap(Textures.bound(), clamp);
    }

    // ================================================================================================ colour / normal

    public static void color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    public static void color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }

    public static void normal3f(float x, float y, float z) {
        Draw.normal(x, y, z);
    }

    // ================================================================================================ matrices

    public static void pushMatrix() {
        current().push();
    }

    public static void popMatrix() {
        current().pop();
    }

    public static void translatef(float x, float y, float z) {
        current().translate(x, y, z);
    }

    public static void translated(double x, double y, double z) {
        current().translate(x, y, z);
    }

    public static void rotatef(float a, float x, float y, float z) {
        current().rotate(a, x, y, z);
    }

    public static void scalef(float x, float y, float z) {
        current().scale(x, y, z);
    }

    public static void scaled(double x, double y, double z) {
        current().scale(x, y, z);
    }

    public static void matrixMode(int newMode) {
        if (newMode == MODELVIEW || newMode == PROJECTION || newMode == TEXTURE) {
            mode = newMode;
        } else {
            unhandled("glMatrixMode", newMode);
        }
    }

    public static void loadIdentity() {
        current().loadIdentity();
    }

    public static void ortho(double l, double r, double b, double t, double n, double f) {
        current().ortho(l, r, b, t, n, f);
    }

    // ================================================================================================ fallback

    private static final java.util.HashSet<String> WARNED = new java.util.HashSet<>();
    private static final java.util.LinkedHashMap<String, String> SITES = new java.util.LinkedHashMap<>();
    private static final int MAX_SITES = 64;

    private static void warnOnce(String what) {
        if (WARNED.add(what)) {
            VkLog.LOGGER.warn("GlShim: {} has no native equivalent, forwarded to the GL safety net", what);
        }
    }

    /** First caller site (class.method:line outside GlShim) per fallback function, at most 64 distinct; for GLAudit. */
    public static synchronized java.util.Map<String, String> fallbackSites() {
        return new java.util.LinkedHashMap<>(SITES);
    }

    private static void recordSite(String function) {
        synchronized (GlShim.class) {
            if (SITES.size() >= MAX_SITES || SITES.containsKey(function)) {
                return;
            }
        }
        String site = StackWalker.getInstance().walk(st -> st.filter(f -> f.getClassName() != GlShim.class.getName()).findFirst())
                .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber()).orElse("?");
        synchronized (GlShim.class) {
            if (SITES.size() < MAX_SITES) {
                SITES.putIfAbsent(function, site);
            }
        }
    }

    /**
     * Forwards a GL call without a typed equivalent to the phase-1 facade (safety net) and counts it. Supported by name: glEnable,
     * glDisable, glBlendFunc, glDepthFunc, glCullFace, glShadeModel, glMatrixMode, glLogicOp, glColorMaterial, glPushAttrib, glFogi,
     * glAlphaFunc (the func only), glTexParameteri; anything else is logged and dropped. The state is mirrored lazily by the safety net, so
     * the effect is visible to emulated draws only.
     */
    public static void unhandled(String function, int... args) {
        FALLBACKS.incrementAndGet();
        recordSite(function);
        warnOnce(function);
        int n = args == null ? 0 : args.length;
        switch (function) {
            case "glEnable" -> {
                if (n == 1) GL11.glEnable(args[0]);
            }
            case "glDisable" -> {
                if (n == 1) GL11.glDisable(args[0]);
            }
            case "glBlendFunc" -> {
                if (n == 2) GL11.glBlendFunc(args[0], args[1]);
            }
            case "glDepthFunc" -> {
                if (n == 1) GL11.glDepthFunc(args[0]);
            }
            case "glCullFace" -> {
                if (n == 1) GL11.glCullFace(args[0]);
            }
            case "glShadeModel" -> {
                if (n == 1) GL11.glShadeModel(args[0]);
            }
            case "glMatrixMode" -> {
                if (n == 1) GL11.glMatrixMode(args[0]);
            }
            case "glLogicOp" -> {
                if (n == 1) GL11.glLogicOp(args[0]);
            }
            case "glColorMaterial" -> {
                if (n == 2) GL11.glColorMaterial(args[0], args[1]);
            }
            case "glPushAttrib" -> {
                if (n == 1) GL11.glPushAttrib(args[0]);
            }
            case "glFogi" -> {
                if (n == 2) GL11.glFogi(args[0], args[1]);
            }
            case "glTexParameteri" -> {
                if (n == 3) GL11.glTexParameteri(args[0], args[1], args[2]);
            }
            default -> VkLog.LOGGER.warn("GlShim.unhandled: '{}' cannot be forwarded and is dropped", function);
        }
    }
}
