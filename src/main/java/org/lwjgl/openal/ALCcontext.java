package org.lwjgl.openal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * LWJGL 2 API: handle of an ALC context. Same layout as LWJGL 2.9.0 (package-private constructor and native pointer field).
 */
public final class ALCcontext {
    /** Native ALCcontext pointer. */
    final long context;
    private volatile boolean valid;

    ALCcontext(long context) {
        this.context = context;
        this.valid = true;
    }

    @Override
    public boolean equals(Object context) {
        if (context instanceof ALCcontext) {
            return ((ALCcontext) context).context == this.context;
        }
        return super.equals(context);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(context);
    }

    /**
     * Builds the attribute list for alcCreateContext: {FREQUENCY, f, REFRESH, r, SYNC, s, 0}. The buffer is direct, in native
     * byte order and flipped (LWJGL 2.9.0 forgot the flip, which made the list start behind its own data).
     */
    static IntBuffer createAttributeList(int contextFrequency, int contextRefresh, int contextSynchronized) {
        IntBuffer attribList = ByteBuffer.allocateDirect(7 * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
        attribList.put(ALC10.ALC_FREQUENCY);
        attribList.put(contextFrequency);
        attribList.put(ALC10.ALC_REFRESH);
        attribList.put(contextRefresh);
        attribList.put(ALC10.ALC_SYNC);
        attribList.put(contextSynchronized);
        attribList.put(0);
        attribList.flip();
        return attribList;
    }

    void setInvalid() {
        valid = false;
    }

    /** @return true if the context is valid (not destroyed, device not closed). */
    public boolean isValid() {
        return valid;
    }
}
