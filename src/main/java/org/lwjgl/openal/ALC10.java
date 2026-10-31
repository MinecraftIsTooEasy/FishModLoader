package org.lwjgl.openal;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import net.xiaoyu233.fml.vulkan.audio.OpenALCore;

/**
 * LWJGL 2 API: OpenAL Context API 1.0 (devices and contexts).
 *
 * <p>Hand-written because it keeps the LWJGL 2 bookkeeping: {@link #devices}/{@link #contexts} map native addresses to the
 * {@link ALCdevice}/{@link ALCcontext} instances handed out to callers, and the package-private {@code nalc*} functions take raw
 * native addresses exactly like the JNI functions of LWJGL 2 (the BetterGameSetting mod calls them through reflection). Only the
 * block between the generated-constants markers is maintained by {@code tools/GenLwjgl2OpenAL.java}; the FFM calls are in
 * {@link OpenALCore}.
 */
public final class ALC10 {
    // <generated-constants>
    public static final int ALC_INVALID = 0;
    public static final int ALC_FALSE = 0;
    public static final int ALC_TRUE = 1;
    public static final int ALC_NO_ERROR = 0;
    public static final int ALC_MAJOR_VERSION = 0x1000;
    public static final int ALC_MINOR_VERSION = 0x1001;
    public static final int ALC_ATTRIBUTES_SIZE = 0x1002;
    public static final int ALC_ALL_ATTRIBUTES = 0x1003;
    public static final int ALC_DEFAULT_DEVICE_SPECIFIER = 0x1004;
    public static final int ALC_DEVICE_SPECIFIER = 0x1005;
    public static final int ALC_EXTENSIONS = 0x1006;
    public static final int ALC_FREQUENCY = 0x1007;
    public static final int ALC_REFRESH = 0x1008;
    public static final int ALC_SYNC = 0x1009;
    public static final int ALC_INVALID_DEVICE = 0xA001;
    public static final int ALC_INVALID_CONTEXT = 0xA002;
    public static final int ALC_INVALID_ENUM = 0xA003;
    public static final int ALC_INVALID_VALUE = 0xA004;
    public static final int ALC_OUT_OF_MEMORY = 0xA005;
    // </generated-constants>

    /** All live contexts by native address (LWJGL 2 internal). */
    static final HashMap<Long, ALCcontext> contexts = new HashMap<>();
    /** All open devices by native address (LWJGL 2 internal). */
    static final HashMap<Long, ALCdevice> devices = new HashMap<>();

    public ALC10() {
    }

    public static String alcGetString(ALCdevice device, int pname) {
        ByteBuffer buffer = nalcGetString(getDevice(device), pname);
        Util.checkALCError(device);
        return decodeUTF8(buffer);
    }

    /** Raw ALC string query. Device lists (device 0) are returned complete, with their terminating NUL characters. */
    static ByteBuffer nalcGetString(long device, int pname) {
        return OpenALCore.nalcGetString(device, pname);
    }

    public static void alcGetInteger(ALCdevice device, int pname, IntBuffer integerdata) {
        OpenALCore.alcGetIntegerv(getDevice(device), pname, integerdata);
        Util.checkALCError(device);
    }

    static void nalcGetIntegerv(long device, int pname, int size, long data) {
        OpenALCore.nalcGetIntegerv(device, pname, size, data);
    }

    /**
     * @param devicename the device specifier, null opens the default device
     * @return the device, or null if it could not be opened
     */
    public static ALCdevice alcOpenDevice(String devicename) {
        long device_address = OpenALCore.alcOpenDevice(devicename);
        if (device_address != 0) {
            ALCdevice device = new ALCdevice(device_address);
            synchronized (devices) {
                devices.put(device_address, device);
            }
            return device;
        }
        return null;
    }

    static long nalcOpenDevice(long devicename) {
        return OpenALCore.nalcOpenDevice(devicename);
    }

    public static boolean alcCloseDevice(ALCdevice device) {
        boolean result = nalcCloseDevice(getDevice(device));
        synchronized (devices) {
            device.setInvalid();
            devices.remove(device.device);
        }
        return result;
    }

    static boolean nalcCloseDevice(long device) {
        return OpenALCore.nalcCloseDevice(device);
    }

    /**
     * @param attrList null-terminated attribute list (ALC_FREQUENCY, ALC_REFRESH, ALC_SYNC...), or null for the defaults
     * @return the context, or null if it could not be created
     */
    public static ALCcontext alcCreateContext(ALCdevice device, IntBuffer attrList) {
        long context_address = OpenALCore.alcCreateContext(getDevice(device), attrList);
        Util.checkALCError(device);
        if (context_address != 0) {
            ALCcontext context = new ALCcontext(context_address);
            synchronized (contexts) {
                contexts.put(context_address, context);
                device.addContext(context);
            }
            return context;
        }
        return null;
    }

    static long nalcCreateContext(long device, long attrList) {
        return OpenALCore.nalcCreateContext(device, attrList);
    }

    /** @param context the context to make current, null releases the current context */
    public static int alcMakeContextCurrent(ALCcontext context) {
        return nalcMakeContextCurrent(getContext(context));
    }

    static int nalcMakeContextCurrent(long context) {
        return OpenALCore.nalcMakeContextCurrent(context);
    }

    public static void alcProcessContext(ALCcontext context) {
        nalcProcessContext(getContext(context));
    }

    static void nalcProcessContext(long context) {
        OpenALCore.nalcProcessContext(context);
    }

    /** @return the current context, or null if there is none (or it was not created through this class) */
    public static ALCcontext alcGetCurrentContext() {
        ALCcontext context = null;
        long context_address = nalcGetCurrentContext();
        if (context_address != 0) {
            synchronized (contexts) {
                context = contexts.get(context_address);
            }
        }
        return context;
    }

    static long nalcGetCurrentContext() {
        return OpenALCore.nalcGetCurrentContext();
    }

    public static ALCdevice alcGetContextsDevice(ALCcontext context) {
        ALCdevice device = null;
        long device_address = nalcGetContextsDevice(getContext(context));
        if (device_address != 0) {
            synchronized (devices) {
                device = devices.get(device_address);
            }
        }
        return device;
    }

    static long nalcGetContextsDevice(long context) {
        return OpenALCore.nalcGetContextsDevice(context);
    }

    public static void alcSuspendContext(ALCcontext context) {
        nalcSuspendContext(getContext(context));
    }

    static void nalcSuspendContext(long context) {
        OpenALCore.nalcSuspendContext(context);
    }

    public static void alcDestroyContext(ALCcontext context) {
        synchronized (contexts) {
            ALCdevice device = alcGetContextsDevice(context);
            nalcDestroyContext(getContext(context));
            if (device != null) {
                device.removeContext(context);
            }
            contexts.remove(context.context);
            context.setInvalid();
        }
    }

    static void nalcDestroyContext(long context) {
        OpenALCore.nalcDestroyContext(context);
    }

    public static int alcGetError(ALCdevice device) {
        return nalcGetError(getDevice(device));
    }

    static int nalcGetError(long device) {
        return OpenALCore.nalcGetError(device);
    }

    public static boolean alcIsExtensionPresent(ALCdevice device, String extName) {
        if (extName == null) {
            throw new IllegalArgumentException("Null argument");
        }
        boolean result = OpenALCore.alcIsExtensionPresent(getDevice(device), extName);
        Util.checkALCError(device);
        return result;
    }

    private static boolean nalcIsExtensionPresent(long device, long extName) {
        return OpenALCore.nalcIsExtensionPresent(device, extName);
    }

    public static int alcGetEnumValue(ALCdevice device, String enumName) {
        if (enumName == null) {
            throw new IllegalArgumentException("Null argument");
        }
        int result = OpenALCore.alcGetEnumValue(getDevice(device), enumName);
        Util.checkALCError(device);
        return result;
    }

    private static int nalcGetEnumValue(long device, long enumName) {
        return OpenALCore.nalcGetEnumValue(device, enumName);
    }

    static long getDevice(ALCdevice device) {
        if (device != null) {
            Util.checkALCValidDevice(device);
            return device.device;
        }
        return 0L;
    }

    static long getContext(ALCcontext context) {
        if (context != null) {
            Util.checkALCValidContext(context);
            return context.context;
        }
        return 0L;
    }

    /**
     * Decodes a native string buffer. Device lists keep their embedded NUL separators; the trailing NULs of the list
     * terminator are dropped.
     */
    private static String decodeUTF8(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        int length = bytes.length;
        while (length > 0 && bytes[length - 1] == 0) {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
