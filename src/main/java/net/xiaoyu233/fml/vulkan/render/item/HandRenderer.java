package net.xiaoyu233.fml.vulkan.render.item;

import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;

/**
 * Static GL-statement handlers for the held-item / first-person hand / overlay paths of {@code ItemRenderer} (3.9, NR-14).
 * Each handler replaces one GL11 statement in place (3.1 table); the MITE arithmetic and instruction order stay untouched.
 */
public final class HandRenderer {
    private HandRenderer() {
    }

    /** Mode-aware, like the other matrix handlers: follows the current glMatrixMode (the glint block uses GL_TEXTURE). */
    public static void push() { GlShim.pushMatrix(); }
    public static void pop() { GlShim.popMatrix(); }
    public static void translate(float x, float y, float z) { GlShim.translatef(x, y, z); }
    public static void rotate(float a, float x, float y, float z) { GlShim.rotatef(a, x, y, z); }
    public static void scale(float x, float y, float z) { GlShim.scalef(x, y, z); }
    public static void color(float r, float g, float b, float a) { Draw.color(r, g, b, a); }
    public static void normal(float x, float y, float z) { Draw.normal(x, y, z); }
    public static void enable(int cap) { GlShim.enable(cap); }
    public static void disable(int cap) { GlShim.disable(cap); }
    public static void blendFunc(int s, int d) { GlShim.blendFunc(s, d); }
    public static void depthFunc(int f) { GlShim.depthFunc(f); }
    public static void matrixMode(int m) { GlShim.matrixMode(m); }
}
