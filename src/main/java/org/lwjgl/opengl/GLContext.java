package org.lwjgl.opengl;

import net.xiaoyu233.fml.vulkan.gl.GLCapabilities;
import org.lwjgl.LWJGLException;

/**
 * LWJGL 2 API: there is no real GL context, the capabilities are the ones decided by
 * {@link GLCapabilities#supported()} (module D1 completes this class).
 *
 * <p>The ContextCapabilities object is cached only once the Vulkan backend is up (the flag set depends on the device) and is
 * rebuilt when {@link GLCapabilities#reset()} signals a backend restart; before that every call builds a fresh, uncached one.
 */
public final class GLContext {
    private static ContextCapabilities capabilities;
    private static int capabilitiesGeneration = -1;

    /** Same public constructor as LWJGL 2.9.0. */
    public GLContext() {
    }

    /** The capabilities of the emulated context. */
    public static ContextCapabilities getCapabilities() {
        if (!GLCapabilities.ready()) {
            return new ContextCapabilities(GLCapabilities.supported());
        }
        synchronized (GLContext.class) {
            final int generation = GLCapabilities.generation();
            if (capabilities == null || capabilitiesGeneration != generation) {
                capabilities = new ContextCapabilities(GLCapabilities.supported());
                capabilitiesGeneration = generation;
            }
            return capabilities;
        }
    }

    public static synchronized void useContext(Object context) throws LWJGLException {
    }

    public static synchronized void useContext(Object context, boolean forwardCompatible) throws LWJGLException {
    }

    public static synchronized void loadOpenGLLibrary() throws LWJGLException {
    }

    public static synchronized void unloadOpenGLLibrary() {
    }
}
