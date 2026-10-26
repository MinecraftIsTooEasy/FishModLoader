package org.lwjgl.openal;

import net.xiaoyu233.fml.vulkan.audio.OpenALCore;
import org.lwjgl.LWJGLException;

/**
 * LWJGL 2 API: creation and destruction of the OpenAL context. The OpenAL Soft library is bundled with FML and bound through
 * Java FFM by {@link OpenALCore} (no JNI, no LWJGL 3 OpenAL classes).
 *
 * <p>The state fields keep their LWJGL 2.9.0 names ({@code device}, {@code context}, {@code created}): mods that open the device
 * themselves (BetterGameSetting) set them through reflection.
 */
public final class AL {
    /** Current device (null if none was opened). */
    static volatile ALCdevice device;
    /** Current context (null if none was created). */
    static volatile ALCcontext context;
    private static volatile boolean created;

    private AL() {
    }

    /** @return true if {@link #create()} succeeded and {@link #destroy()} was not called since */
    public static boolean isCreated() {
        return created;
    }

    /**
     * Creates the OpenAL context on the default device: 44100 Hz, 60 Hz refresh, not synchronous.
     *
     * @throws LWJGLException if the library or the device could not be opened
     */
    public static void create() throws LWJGLException {
        create(null, 44100, 60, false);
    }

    /**
     * Creates the OpenAL context.
     *
     * @param deviceArg device specifier, null selects the default device
     * @param contextFrequency output frequency in Hz, -1 leaves it to the implementation
     * @param contextRefresh refresh rate in Hz
     * @param contextSynchronized synchronous context
     * @throws LWJGLException if the library or the device could not be opened
     */
    public static void create(String deviceArg, int contextFrequency, int contextRefresh, boolean contextSynchronized) throws LWJGLException {
        create(deviceArg, contextFrequency, contextRefresh, contextSynchronized, true);
    }

    /**
     * Creates the OpenAL context.
     *
     * @param openDevice false only loads the library: no device and no context is created, the caller opens them itself
     * @throws LWJGLException if the library or the device could not be opened
     * @see #create(String, int, int, boolean)
     */
    public static synchronized void create(String deviceArg, int contextFrequency, int contextRefresh, boolean contextSynchronized, boolean openDevice) throws LWJGLException {
        if (created) {
            throw new IllegalStateException("Only one OpenAL context may be instantiated at any one time.");
        }
        try {
            OpenALCore.load();
        } catch (Throwable t) {
            throw new LWJGLException("Could not locate OpenAL library.", t);
        }
        created = true;
        init(deviceArg, contextFrequency, contextRefresh, contextSynchronized, openDevice);
    }

    private static void init(String deviceArg, int contextFrequency, int contextRefresh, boolean contextSynchronized, boolean openDevice) throws LWJGLException {
        try {
            if (openDevice) {
                device = ALC10.alcOpenDevice(deviceArg);
                if (device == null) {
                    throw new LWJGLException("Could not open ALC device");
                }
                if (contextFrequency == -1) {
                    context = ALC10.alcCreateContext(device, null);
                } else {
                    context = ALC10.alcCreateContext(device, ALCcontext.createAttributeList(contextFrequency, contextRefresh, contextSynchronized ? ALC10.ALC_TRUE : ALC10.ALC_FALSE));
                }
                if (context == null) {
                    throw new LWJGLException("Could not create ALC context");
                }
                if (ALC10.alcMakeContextCurrent(context) == ALC10.ALC_FALSE) {
                    throw new LWJGLException("Could not make the ALC context current");
                }
            }
        } catch (LWJGLException e) {
            destroy();
            throw e;
        } catch (RuntimeException | LinkageError e) {
            destroy();
            throw new LWJGLException("Failed to create the OpenAL context: " + e, e);
        }
    }

    /** Destroys the context and closes the device (whoever opened them). Safe to call at any time. */
    public static synchronized void destroy() {
        ALCcontext oldContext = context;
        ALCdevice oldDevice = device;
        context = null;
        device = null;
        try {
            if (oldContext != null) {
                try {
                    ALC10.alcMakeContextCurrent(null);
                    ALC10.alcDestroyContext(oldContext);
                } catch (RuntimeException e) {
                    OpenALCore.warn("Failed to destroy the OpenAL context", e);
                }
            }
            if (oldDevice != null) {
                try {
                    ALC10.alcCloseDevice(oldDevice);
                } catch (RuntimeException e) {
                    OpenALCore.warn("Failed to close the OpenAL device", e);
                }
            }
        } finally {
            created = false;
        }
    }

    /** @return the current context, null if none was created */
    public static ALCcontext getContext() {
        return context;
    }

    /** @return the current device, null if none was opened */
    public static ALCdevice getDevice() {
        return device;
    }
}
