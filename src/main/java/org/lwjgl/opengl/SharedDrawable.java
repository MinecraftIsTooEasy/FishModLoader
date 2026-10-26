package org.lwjgl.opengl;

import net.xiaoyu233.fml.vulkan.VkLog;
import org.lwjgl.LWJGLException;
import org.lwjgl.PointerBuffer;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LWJGL 2 API: a drawable sharing the objects of another one, used to feed a context from a loader thread. There are no GL
 * contexts in the Vulkan port and GL calls are only valid on the render thread, so this is a handle that does nothing
 * (module E). The first {@code makeCurrent} on another thread is logged once.
 */
public final class SharedDrawable implements Drawable {
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private boolean destroyed;

    public SharedDrawable(Drawable drawable) throws LWJGLException {
        if (drawable == null) {
            throw new LWJGLException("The drawable to share must not be null");
        }
    }

    @Override
    public boolean isCurrent() throws LWJGLException {
        return !destroyed;
    }

    @Override
    public void makeCurrent() throws LWJGLException {
        if (WARNED.compareAndSet(false, true)) {
            VkLog.LOGGER.warn("SharedDrawable.makeCurrent(): there are no GL contexts in the Vulkan port, GL calls are only valid on the render thread");
        }
    }

    @Override
    public void releaseContext() throws LWJGLException {
    }

    @Override
    public void destroy() {
        destroyed = true;
    }

    @Override
    public void setCLSharingProperties(PointerBuffer properties) throws LWJGLException {
        throw new LWJGLException("OpenCL sharing is not supported");
    }
}
