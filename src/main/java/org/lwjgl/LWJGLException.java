package org.lwjgl;

/**
 * LWJGL 2 API: general exception of the library (identical to LWJGL 2.9.0). Owned by module E.
 */
public class LWJGLException extends Exception {
    private static final long serialVersionUID = 1L;

    public LWJGLException() {
        super();
    }

    public LWJGLException(String msg) {
        super(msg);
    }

    public LWJGLException(String message, Throwable cause) {
        super(message, cause);
    }

    public LWJGLException(Throwable cause) {
        super(cause);
    }
}
