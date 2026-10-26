package org.lwjgl.opengl;

import org.lwjgl.LWJGLException;
import org.lwjgl.PointerBuffer;

/** LWJGL 2 API: something that can be made current for rendering (identical to LWJGL 2.9.0). Owned by module E. */
public interface Drawable {
    boolean isCurrent() throws LWJGLException;

    void makeCurrent() throws LWJGLException;

    void releaseContext() throws LWJGLException;

    void destroy();

    void setCLSharingProperties(PointerBuffer properties) throws LWJGLException;
}
