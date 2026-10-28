package net.xiaoyu233.fml.vulkan.render.legacy;

import java.nio.ByteOrder;
import net.xiaoyu233.fml.vulkan.gl.state.GLState;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Entry of {@code NativeTess} LEGACY_LIST submissions into the display-list emulation (1.6). The raw MITE Tessellator vertices
 * (8 ints each: x,y,z float, u,v float, rgba bytes, normal bytes, brightness as two shorts) are replayed as immediate-mode geometry
 * ({@code glBegin(glMode) ... glEnd}). When a display list is being compiled the GL facade records these calls into the list; otherwise
 * they draw immediately, exactly like the old {@code Tessellator.draw()}.
 * <p>
 * The replay sets the emulation's current colour, normal and texture coordinates per vertex. The old {@code Tessellator.draw()} used
 * vertex arrays and never changed those current attributes, so they are restored once the vertices are issued, before {@code glEnd}.
 * The restore writes the saved values straight into the state arrays and puts back the legacy dirty bits, so a tessellator draw does
 * not push colour, normal or texcoord into {@code Draw} and does not count as a state sync.
 */
public final class LegacyTessSubmit {
    private static final int GL_TEXTURE1 = 33985;
    private static final boolean LE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    /** Saved current attributes: colour (4), normal (3), unit 0 texcoord (4), unit 1 texcoord (4). Render thread only. */
    private static final float[] SAVED = new float[15];

    private LegacyTessSubmit() {
    }

    private static void saveCurrent(GLState st) {
        System.arraycopy(st.currentColor(), 0, SAVED, 0, 4);
        System.arraycopy(st.currentNormal(), 0, SAVED, 4, 3);
        System.arraycopy(st.currentTexCoord(0), 0, SAVED, 7, 4);
        System.arraycopy(st.currentTexCoord(1), 0, SAVED, 11, 4);
    }

    private static void restoreCurrent(GLState st) {
        System.arraycopy(SAVED, 0, st.currentColor(), 0, 4);
        System.arraycopy(SAVED, 4, st.currentNormal(), 0, 3);
        System.arraycopy(SAVED, 7, st.currentTexCoord(0), 0, 4);
        System.arraycopy(SAVED, 11, st.currentTexCoord(1), 0, 4);
    }

    public static void submit(int[] raw, int intCount, int vertexCount, int glMode, boolean hasTexture, boolean hasColor, boolean hasNormals, boolean hasBrightness) {
        if (vertexCount <= 0) {
            return;
        }
        GL11.glBegin(glMode);
        // Saved after glBegin: its hook may pull native state into the emulation first.
        final GLState st = GLState.get();
        final int savedLegacy = StateMirror.legacyDirty;
        saveCurrent(st);
        // Consecutive vertices usually share brightness, colour and normal. A packed value equal to the previous vertex's is not
        // sent again: the current attribute already holds it, so the emitted vertices are unchanged.
        int prevBright = 0, prevColor = 0, prevNormal = 0;
        boolean haveBright = false, haveColor = false, haveNormal = false;
        for (int v = 0; v < vertexCount; v++) {
            final int o = v * 8;
            if (o + 7 >= raw.length) {
                break;
            }
            if (hasTexture) {
                GL11.glTexCoord2f(Float.intBitsToFloat(raw[o + 3]), Float.intBitsToFloat(raw[o + 4]));
            }
            if (hasBrightness) {
                final int b = raw[o + 7];
                if (!haveBright || b != prevBright) {
                    haveBright = true;
                    prevBright = b;
                    final int lo = LE ? (b & 0xFFFF) : (b >>> 16);
                    final int hi = LE ? (b >>> 16) : (b & 0xFFFF);
                    GL13.glMultiTexCoord2f(GL_TEXTURE1, (short) lo, (short) hi);
                }
            }
            if (hasColor) {
                final int c = raw[o + 5];
                if (!haveColor || c != prevColor) {
                    haveColor = true;
                    prevColor = c;
                    final int r, g, bl, a;
                    if (LE) {
                        r = c & 255; g = (c >>> 8) & 255; bl = (c >>> 16) & 255; a = c >>> 24;
                    } else {
                        r = c >>> 24; g = (c >>> 16) & 255; bl = (c >>> 8) & 255; a = c & 255;
                    }
                    GL11.glColor4f(r / 255f, g / 255f, bl / 255f, a / 255f);
                }
            }
            if (hasNormals) {
                final int n = raw[o + 6];
                if (!haveNormal || n != prevNormal) {
                    haveNormal = true;
                    prevNormal = n;
                    final int x, y, z;
                    if (LE) {
                        x = (byte) n; y = (byte) (n >> 8); z = (byte) (n >> 16);
                    } else {
                        x = (byte) (n >> 24); y = (byte) (n >> 16); z = (byte) (n >> 8);
                    }
                    GL11.glNormal3f(x / 127f, y / 127f, z / 127f);
                }
            }
            GL11.glVertex3f(Float.intBitsToFloat(raw[o]), Float.intBitsToFloat(raw[o + 1]), Float.intBitsToFloat(raw[o + 2]));
        }
        restoreCurrent(st);
        StateMirror.legacyDirty = savedLegacy;
        GL11.glEnd();
    }
}
