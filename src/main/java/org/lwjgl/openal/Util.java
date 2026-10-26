package org.lwjgl.openal;

/**
 * LWJGL 2 API: OpenAL error checking helpers.
 */
public final class Util {
    private Util() {
    }

    /**
     * Checks for an ALC error on the device and throws an {@link OpenALException} (message = ALC error string) if there is one.
     *
     * @param device the device to check, null checks the global ALC error
     */
    public static void checkALCError(ALCdevice device) {
        int err = ALC10.alcGetError(device);
        if (err != ALC10.ALC_NO_ERROR) {
            throw new OpenALException(ALC10.alcGetString(AL.getDevice(), err));
        }
    }

    /** Checks for an AL error and throws an {@link OpenALException} if there is one. */
    public static void checkALError() {
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR) {
            throw new OpenALException(err);
        }
    }

    /** Throws an {@link OpenALException} if the device is not valid. */
    public static void checkALCValidDevice(ALCdevice device) {
        if (!device.isValid()) {
            throw new OpenALException("Invalid device: " + device);
        }
    }

    /** Throws an {@link OpenALException} if the context is not valid. */
    public static void checkALCValidContext(ALCcontext context) {
        if (!context.isValid()) {
            throw new OpenALException("Invalid context: " + context);
        }
    }
}
