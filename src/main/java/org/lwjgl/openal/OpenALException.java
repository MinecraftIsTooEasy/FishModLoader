package org.lwjgl.openal;

/**
 * LWJGL 2 API: unchecked exception for OpenAL errors (see {@link Util#checkALError()}).
 */
public class OpenALException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OpenALException() {
        super();
    }

    public OpenALException(int error_code) {
        super("OpenAL error: " + errorString(error_code) + " (" + error_code + ")");
    }

    public OpenALException(String message) {
        super(message);
    }

    public OpenALException(String message, Throwable cause) {
        super(message, cause);
    }

    public OpenALException(Throwable cause) {
        super(cause);
    }

    /** Text of an AL error code; never fails (the message of an exception must not hide the original error). */
    private static String errorString(int error_code) {
        try {
            return AL10.alGetString(error_code);
        } catch (RuntimeException | LinkageError e) {
            return "0x" + Integer.toHexString(error_code);
        }
    }
}
