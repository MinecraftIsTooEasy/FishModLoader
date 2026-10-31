package org.lwjgl.opengl;

/**
 * LWJGL 2 API: GL error helpers (identical to LWJGL 2.9.0; the error flag is {@code net.xiaoyu233.fml.vulkan.gl.GLErrors}).
 */
public final class Util {
    /** No c'tor */
    private Util() {
    }

    /**
     * Checks for OpenGL errors and throws an {@link OpenGLException} when one is pending.
     */
    public static void checkGLError() throws OpenGLException {
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            throw new OpenGLException(err);
        }
    }

    /**
     * Translates a GL error code to a human readable text, null for unknown codes.
     */
    public static String translateGLErrorString(int error_code) {
        switch (error_code) {
            case GL11.GL_NO_ERROR:
                return "No error";
            case GL11.GL_INVALID_ENUM:
                return "Invalid enum";
            case GL11.GL_INVALID_VALUE:
                return "Invalid value";
            case GL11.GL_INVALID_OPERATION:
                return "Invalid operation";
            case GL11.GL_STACK_OVERFLOW:
                return "Stack overflow";
            case GL11.GL_STACK_UNDERFLOW:
                return "Stack underflow";
            case GL11.GL_OUT_OF_MEMORY:
                return "Out of memory";
            case 0x8031: // ARBImaging.GL_TABLE_TOO_LARGE (ARB_imaging is not part of the compat surface)
                return "Table too large";
            case EXTFramebufferObject.GL_INVALID_FRAMEBUFFER_OPERATION_EXT:
                return "Invalid framebuffer operation";
            default:
                return null;
        }
    }
}
