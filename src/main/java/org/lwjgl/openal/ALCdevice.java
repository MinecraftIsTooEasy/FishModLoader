package org.lwjgl.openal;

import java.util.HashMap;

/**
 * LWJGL 2 API: handle of an ALC device. Instances are created by {@link ALC10}/{@link ALC11} and registered in
 * {@code ALC10.devices}; the layout (package-private constructor, fields and bookkeeping methods) is the one of LWJGL 2.9.0
 * because some mods build and register handles themselves through reflection.
 */
public final class ALCdevice {
    /** Native ALCdevice pointer. */
    final long device;
    private volatile boolean valid;
    /** Contexts created on this device, by native address. */
    private final HashMap<Long, ALCcontext> contexts = new HashMap<>();

    ALCdevice(long device) {
        this.device = device;
        this.valid = true;
    }

    @Override
    public boolean equals(Object device) {
        if (device instanceof ALCdevice) {
            return ((ALCdevice) device).device == this.device;
        }
        return super.equals(device);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(device);
    }

    void addContext(ALCcontext context) {
        synchronized (contexts) {
            contexts.put(context.context, context);
        }
    }

    void removeContext(ALCcontext context) {
        synchronized (contexts) {
            contexts.remove(context.context);
        }
    }

    /** Invalidates this device and every context created on it. */
    void setInvalid() {
        valid = false;
        synchronized (contexts) {
            for (ALCcontext context : contexts.values()) {
                context.setInvalid();
            }
            contexts.clear();
        }
    }

    /** @return true if the device is valid (not closed). */
    public boolean isValid() {
        return valid;
    }
}
