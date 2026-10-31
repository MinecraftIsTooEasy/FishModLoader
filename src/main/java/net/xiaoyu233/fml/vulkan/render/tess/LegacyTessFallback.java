package net.xiaoyu233.fml.vulkan.render.tess;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Private fallback of {@link NativeTess} that replays raw Tessellator vertices through the phase-1 GL client-array API, exactly like
 * MITE's {@code TessellatorMITE.draw} did. It is used (a) while the safety net compiles a display list and
 * {@code LegacyTessSubmit} is not implemented yet and (b) for every draw when the batching layer is not available. This is the only
 * place of the tessellator front-end that talks GL; it disappears from the hot path as soon as NR-03 and NR-08 land.
 */
final class LegacyTessFallback {
    private static final int GL_TEXTURE0 = 33984, GL_TEXTURE1 = 33985;
    private static final int TEXTURE_COORD_ARRAY = 32888, COLOR_ARRAY = 32886, NORMAL_ARRAY = 32885, VERTEX_ARRAY = 32884;

    private static ByteBuffer bytes = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());

    private LegacyTessFallback() {
    }

    static void draw(int[] raw, int intCount, int vertexCount, int glMode, boolean hasTexture, boolean hasColor, boolean hasNormals, boolean hasBrightness) {
        if (vertexCount <= 0) {
            return;
        }
        int needed = intCount * 4;
        if (bytes.capacity() < needed) {
            int cap = bytes.capacity();
            while (cap < needed) {
                cap <<= 1;
            }
            bytes = ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder());
        }
        final ByteBuffer b = bytes;
        ((java.nio.Buffer) b).clear();
        b.asIntBuffer().put(raw, 0, intCount);
        ((java.nio.Buffer) b).position(0);
        ((java.nio.Buffer) b).limit(needed);
        final FloatBuffer floats = b.asFloatBuffer();
        final ShortBuffer shorts = b.asShortBuffer();
        if (hasTexture) {
            ((java.nio.Buffer) floats).position(3);
            GL11.glTexCoordPointer(2, 32, floats);
            GL11.glEnableClientState(TEXTURE_COORD_ARRAY);
        }
        if (hasBrightness) {
            GL13.glClientActiveTexture(GL_TEXTURE1);
            ((java.nio.Buffer) shorts).position(14);
            GL11.glTexCoordPointer(2, 32, shorts);
            GL11.glEnableClientState(TEXTURE_COORD_ARRAY);
            GL13.glClientActiveTexture(GL_TEXTURE0);
        }
        if (hasColor) {
            ((java.nio.Buffer) b).position(20);
            GL11.glColorPointer(4, true, 32, b);
            GL11.glEnableClientState(COLOR_ARRAY);
        }
        if (hasNormals) {
            ((java.nio.Buffer) b).position(24);
            GL11.glNormalPointer(32, b);
            GL11.glEnableClientState(NORMAL_ARRAY);
        }
        ((java.nio.Buffer) floats).position(0);
        GL11.glVertexPointer(3, 32, floats);
        GL11.glEnableClientState(VERTEX_ARRAY);
        GL11.glDrawArrays(glMode, 0, vertexCount);
        GL11.glDisableClientState(VERTEX_ARRAY);
        if (hasTexture) {
            GL11.glDisableClientState(TEXTURE_COORD_ARRAY);
        }
        if (hasBrightness) {
            GL13.glClientActiveTexture(GL_TEXTURE1);
            GL11.glDisableClientState(TEXTURE_COORD_ARRAY);
            GL13.glClientActiveTexture(GL_TEXTURE0);
        }
        if (hasColor) {
            GL11.glDisableClientState(COLOR_ARRAY);
        }
        if (hasNormals) {
            GL11.glDisableClientState(NORMAL_ARRAY);
        }
    }
}
