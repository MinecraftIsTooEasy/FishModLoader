package net.xiaoyu233.fml.vulkan.audio;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.B;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.D;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.F;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.I;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.L;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.P;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.S;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.core;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.ext;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.r;
import static net.xiaoyu233.fml.vulkan.audio.AlNatives.v;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * OpenAL 1.0/1.1, ALC 1.0/1.1 and EFX 1.0 through Java FFM: the implementation behind the LWJGL 2 compat classes in
 * {@code org.lwjgl.openal}.
 *
 * <ul>
 *   <li>The AL/EFX methods carry exactly the names and descriptors of the LWJGL 2.9.0 API (the generated facades
 *       {@code AL10}, {@code AL11} and {@code EFX10} delegate 1:1).</li>
 *   <li>The ALC functions work on raw handles ({@code nalc*}: native addresses as {@code long}, like the JNI layer of LWJGL 2);
 *       the wrapper objects and their registries live in {@code ALC10}/{@code ALC11}.</li>
 *   <li>Buffers: direct buffers are passed to OpenAL without a copy (from their position, {@code remaining()} elements),
 *       heap buffers are copied through temporary native memory. Buffers that OpenAL writes to are checked for the number
 *       of elements the call can produce.</li>
 *   <li>Thread-safe: OpenAL Soft is, and this class keeps no state. All functions are bound once ({@link AlNatives}); one that
 *       the library lacks throws {@link UnsupportedOperationException} when called.</li>
 * </ul>
 */
public final class OpenALCore {
    private static final int AL_POSITION = 0x1004;
    private static final int AL_DIRECTION = 0x1005;
    private static final int AL_VELOCITY = 0x1006;
    private static final int AL_ORIENTATION = 0x100F;
    private static final int ALC_DEVICE_SPECIFIER = 0x1005;
    private static final int ALC_ALL_DEVICES_SPECIFIER = 0x1013;
    private static final int ALC_CAPTURE_DEVICE_SPECIFIER = 0x310;

    private OpenALCore() {
    }

    // ---------------------------------------------------------------- library

    /**
     * Loads the OpenAL library if that has not happened yet.
     *
     * @throws UnsatisfiedLinkError if no usable library was found (the message lists everything that was tried)
     */
    public static void load() {
        OpenALLibrary.lookup();
    }

    /** @return where the OpenAL library was loaded from, null before it was loaded. */
    public static String libraryOrigin() {
        return OpenALLibrary.origin();
    }

    /** Logs a problem of the audio layer. */
    public static void warn(String message, Throwable error) {
        AudioLog.warn(message, error);
    }

    // ---------------------------------------------------------------- function handles

    /** Core AL and ALC entry points. */
    private static final class H {
        static final MethodHandle alEnable = core("alEnable", v(I));
        static final MethodHandle alDisable = core("alDisable", v(I));
        static final MethodHandle alIsEnabled = core("alIsEnabled", r(B, I));
        static final MethodHandle alGetBoolean = core("alGetBoolean", r(B, I));
        static final MethodHandle alGetInteger = core("alGetInteger", r(I, I));
        static final MethodHandle alGetFloat = core("alGetFloat", r(F, I));
        static final MethodHandle alGetDouble = core("alGetDouble", r(D, I));
        static final MethodHandle alGetIntegerv = core("alGetIntegerv", v(I, P));
        static final MethodHandle alGetFloatv = core("alGetFloatv", v(I, P));
        static final MethodHandle alGetDoublev = core("alGetDoublev", v(I, P));
        static final MethodHandle alGetString = core("alGetString", r(S, I));
        static final MethodHandle alGetError = core("alGetError", r(I));
        static final MethodHandle alIsExtensionPresent = core("alIsExtensionPresent", r(B, P));
        static final MethodHandle alGetEnumValue = core("alGetEnumValue", r(I, P));

        static final MethodHandle alListeneri = core("alListeneri", v(I, I));
        static final MethodHandle alListenerf = core("alListenerf", v(I, F));
        static final MethodHandle alListenerfv = core("alListenerfv", v(I, P));
        static final MethodHandle alListener3f = core("alListener3f", v(I, F, F, F));
        static final MethodHandle alListener3i = core("alListener3i", v(I, I, I, I));
        static final MethodHandle alGetListeneri = core("alGetListeneri", v(I, P));
        static final MethodHandle alGetListenerf = core("alGetListenerf", v(I, P));
        static final MethodHandle alGetListenerfv = core("alGetListenerfv", v(I, P));
        static final MethodHandle alGetListeneriv = core("alGetListeneriv", v(I, P));

        static final MethodHandle alGenSources = core("alGenSources", v(I, P));
        static final MethodHandle alDeleteSources = core("alDeleteSources", v(I, P));
        static final MethodHandle alIsSource = core("alIsSource", r(B, I));
        static final MethodHandle alSourcei = core("alSourcei", v(I, I, I));
        static final MethodHandle alSourcef = core("alSourcef", v(I, I, F));
        static final MethodHandle alSourcefv = core("alSourcefv", v(I, I, P));
        static final MethodHandle alSource3f = core("alSource3f", v(I, I, F, F, F));
        static final MethodHandle alSource3i = core("alSource3i", v(I, I, I, I, I));
        static final MethodHandle alSourceiv = core("alSourceiv", v(I, I, P));
        static final MethodHandle alGetSourcei = core("alGetSourcei", v(I, I, P));
        static final MethodHandle alGetSourcef = core("alGetSourcef", v(I, I, P));
        static final MethodHandle alGetSourcefv = core("alGetSourcefv", v(I, I, P));
        static final MethodHandle alSourcePlayv = core("alSourcePlayv", v(I, P));
        static final MethodHandle alSourcePausev = core("alSourcePausev", v(I, P));
        static final MethodHandle alSourceStopv = core("alSourceStopv", v(I, P));
        static final MethodHandle alSourceRewindv = core("alSourceRewindv", v(I, P));
        static final MethodHandle alSourcePlay = core("alSourcePlay", v(I));
        static final MethodHandle alSourcePause = core("alSourcePause", v(I));
        static final MethodHandle alSourceStop = core("alSourceStop", v(I));
        static final MethodHandle alSourceRewind = core("alSourceRewind", v(I));
        static final MethodHandle alSourceQueueBuffers = core("alSourceQueueBuffers", v(I, I, P));
        static final MethodHandle alSourceUnqueueBuffers = core("alSourceUnqueueBuffers", v(I, I, P));

        static final MethodHandle alGenBuffers = core("alGenBuffers", v(I, P));
        static final MethodHandle alDeleteBuffers = core("alDeleteBuffers", v(I, P));
        static final MethodHandle alIsBuffer = core("alIsBuffer", r(B, I));
        /** alBufferData(buffer, format, data, size, freq): the size is in bytes. */
        static final MethodHandle alBufferData = core("alBufferData", v(I, I, P, I, I));
        static final MethodHandle alBufferf = core("alBufferf", v(I, I, F));
        static final MethodHandle alBuffer3f = core("alBuffer3f", v(I, I, F, F, F));
        static final MethodHandle alBufferfv = core("alBufferfv", v(I, I, P));
        static final MethodHandle alBufferi = core("alBufferi", v(I, I, I));
        static final MethodHandle alBuffer3i = core("alBuffer3i", v(I, I, I, I, I));
        static final MethodHandle alBufferiv = core("alBufferiv", v(I, I, P));
        static final MethodHandle alGetBufferi = core("alGetBufferi", v(I, I, P));
        static final MethodHandle alGetBufferf = core("alGetBufferf", v(I, I, P));
        static final MethodHandle alGetBufferiv = core("alGetBufferiv", v(I, I, P));
        static final MethodHandle alGetBufferfv = core("alGetBufferfv", v(I, I, P));

        static final MethodHandle alDistanceModel = core("alDistanceModel", v(I));
        static final MethodHandle alDopplerFactor = core("alDopplerFactor", v(F));
        static final MethodHandle alDopplerVelocity = core("alDopplerVelocity", v(F));
        static final MethodHandle alSpeedOfSound = core("alSpeedOfSound", v(F));

        static final MethodHandle alcOpenDevice = core("alcOpenDevice", r(L, P));
        static final MethodHandle alcCloseDevice = core("alcCloseDevice", r(B, L));
        static final MethodHandle alcGetString = core("alcGetString", r(S, L, I));
        static final MethodHandle alcGetIntegerv = core("alcGetIntegerv", v(L, I, I, P));
        static final MethodHandle alcCreateContext = core("alcCreateContext", r(L, L, P));
        static final MethodHandle alcMakeContextCurrent = core("alcMakeContextCurrent", r(B, L));
        static final MethodHandle alcProcessContext = core("alcProcessContext", v(L));
        static final MethodHandle alcGetCurrentContext = core("alcGetCurrentContext", r(L));
        static final MethodHandle alcGetContextsDevice = core("alcGetContextsDevice", r(L, L));
        static final MethodHandle alcSuspendContext = core("alcSuspendContext", v(L));
        static final MethodHandle alcDestroyContext = core("alcDestroyContext", v(L));
        static final MethodHandle alcGetError = core("alcGetError", r(I, L));
        static final MethodHandle alcIsExtensionPresent = core("alcIsExtensionPresent", r(B, L, P));
        static final MethodHandle alcGetEnumValue = core("alcGetEnumValue", r(I, L, P));
        static final MethodHandle alcCaptureOpenDevice = core("alcCaptureOpenDevice", r(L, P, I, I, I));
        static final MethodHandle alcCaptureCloseDevice = core("alcCaptureCloseDevice", r(B, L));
        static final MethodHandle alcCaptureStart = core("alcCaptureStart", v(L));
        static final MethodHandle alcCaptureStop = core("alcCaptureStop", v(L));
        static final MethodHandle alcCaptureSamples = core("alcCaptureSamples", v(L, P, I));

        private H() {
        }
    }

    /** The functions of one EFX object family (effects, filters, auxiliary effect slots) share one shape. */
    private static final class Family {
        final MethodHandle gen, del, is, seti, setiv, setf, setfv, geti, getiv, getf, getfv;

        Family(String singular, String plural) {
            gen = ext("alGen" + plural, v(I, P));
            del = ext("alDelete" + plural, v(I, P));
            is = ext("alIs" + singular, r(B, I));
            seti = ext("al" + singular + "i", v(I, I, I));
            setiv = ext("al" + singular + "iv", v(I, I, P));
            setf = ext("al" + singular + "f", v(I, I, F));
            setfv = ext("al" + singular + "fv", v(I, I, P));
            geti = ext("alGet" + singular + "i", v(I, I, P));
            getiv = ext("alGet" + singular + "iv", v(I, I, P));
            getf = ext("alGet" + singular + "f", v(I, I, P));
            getfv = ext("alGet" + singular + "fv", v(I, I, P));
        }
    }

    /** EFX entry points, bound on first use (exported symbol or alGetProcAddress). */
    private static final class E {
        static final Family EFFECT = new Family("Effect", "Effects");
        static final Family FILTER = new Family("Filter", "Filters");
        static final Family SLOT = new Family("AuxiliaryEffectSlot", "AuxiliaryEffectSlots");

        private E() {
        }
    }

    // ---------------------------------------------------------------- helpers

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) return e;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new IllegalArgumentException("Null argument");
        }
    }

    private static void checkBuffer(Buffer buffer, int minimum) {
        checkNotNull(buffer);
        if (buffer.remaining() < minimum) {
            throw new IllegalArgumentException("Number of remaining buffer elements is " + buffer.remaining()
                    + ", must be at least " + minimum + ". Because at most " + minimum + " elements can be returned, a buffer with at least "
                    + minimum + " elements is required, regardless of actual returned element count");
        }
    }

    /** An ALC attribute list is a sequence of (key, value) pairs ended by a 0 key: OpenAL reads until it finds it. */
    private static void checkNullTerminated(IntBuffer attrList) {
        for (int i = attrList.position(); i < attrList.limit(); i += 2) {
            if (attrList.get(i) == 0) {
                return;
            }
        }
        throw new IllegalArgumentException("Attribute list is not null-terminated");
    }

    /** Number of floats/ints of a listener parameter. */
    private static int listenerCount(int pname) {
        return switch (pname) {
            case AL_ORIENTATION -> 6;
            case AL_POSITION, AL_VELOCITY -> 3;
            default -> 1;
        };
    }

    /** Number of floats/ints of a source parameter. */
    private static int sourceCount(int pname) {
        return switch (pname) {
            case AL_POSITION, AL_VELOCITY, AL_DIRECTION -> 3;
            default -> 1;
        };
    }

    /**
     * Native view of the remaining elements of a buffer. A direct buffer is used in place; a heap buffer is copied to temporary
     * native memory, {@link #commit()} copies the (possibly modified) data back.
     */
    private static final class Pin implements AutoCloseable {
        final MemorySegment seg;
        private final MemorySegment heap;
        private final Arena arena;

        Pin(Buffer buffer) {
            MemorySegment s = MemorySegment.ofBuffer(buffer);
            if (s.isNative()) {
                seg = s;
                heap = null;
                arena = null;
            } else {
                arena = Arena.ofConfined();
                seg = arena.allocate(Math.max(s.byteSize(), 1L), 16L).asSlice(0, s.byteSize());
                seg.copyFrom(s);
                heap = s;
            }
        }

        void commit() {
            if (heap != null) {
                heap.copyFrom(seg);
            }
        }

        @Override
        public void close() {
            if (arena != null) {
                arena.close();
            }
        }
    }

    /** h(count, pointer) that fills the buffer (alGen*, alSourceUnqueueBuffers is separate). */
    private static void idsOut(MethodHandle h, IntBuffer ids) {
        checkNotNull(ids);
        try (Pin p = new Pin(ids)) {
            h.invokeExact(ids.remaining(), p.seg);
            p.commit();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** h(count, pointer) that reads the buffer (alDelete*, alSourcePlayv...). */
    private static void idsIn(MethodHandle h, IntBuffer ids) {
        checkNotNull(ids);
        try (Pin p = new Pin(ids)) {
            h.invokeExact(ids.remaining(), p.seg);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** h(pname, pointer): the call reads ({@code write} false) or fills the buffer. */
    private static void ptr2(MethodHandle h, int pname, Buffer buffer, int minimum, boolean write) {
        checkBuffer(buffer, minimum);
        try (Pin p = new Pin(buffer)) {
            h.invokeExact(pname, p.seg);
            if (write) p.commit();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** h(object, pname, pointer): the call reads ({@code write} false) or fills the buffer. */
    private static void ptr3(MethodHandle h, int object, int pname, Buffer buffer, int minimum, boolean write) {
        checkBuffer(buffer, minimum);
        try (Pin p = new Pin(buffer)) {
            h.invokeExact(object, pname, p.seg);
            if (write) p.commit();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * h(object, pname, pointer) that fills a buffer whose required size depends on the parameter (EFX vectors): OpenAL writes
     * to a temporary area that is large enough for every parameter, the caller's buffer gets as much as fits.
     */
    private static void ptr3Safe(MethodHandle h, int object, int pname, Buffer buffer, boolean floats) {
        checkBuffer(buffer, 1);
        MemorySegment target = MemorySegment.ofBuffer(buffer);
        int width = floats ? Float.BYTES : Integer.BYTES;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment tmp = a.allocate(Math.max(target.byteSize(), 16L * width), 16L);
            tmp.asSlice(0, target.byteSize()).copyFrom(target);
            h.invokeExact(object, pname, tmp);
            target.copyFrom(tmp.asSlice(0, target.byteSize()));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static int outInt3(MethodHandle h, int a, int b) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            h.invokeExact(a, b, out);
            return out.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static float outFloat3(MethodHandle h, int a, int b) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_FLOAT);
            h.invokeExact(a, b, out);
            return out.get(JAVA_FLOAT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static void set3i(MethodHandle h, int a, int b, int c) {
        try {
            h.invokeExact(a, b, c);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static void set3f(MethodHandle h, int a, int b, float c) {
        try {
            h.invokeExact(a, b, c);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static boolean isTrue(MethodHandle h, int id) {
        try {
            return (byte) h.invokeExact(id) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The one-element variants: alGen*(1) / alDelete*(1) with a single id. */
    private static int genOne(MethodHandle h) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT);
            h.invokeExact(1, out);
            return out.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static void deleteOne(MethodHandle h, int id) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment in = a.allocate(JAVA_INT);
            in.set(JAVA_INT, 0, id);
            h.invokeExact(1, in);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static String string(MemorySegment s) {
        return s.address() == 0 ? null : s.getString(0);
    }

    // ---------------------------------------------------------------- AL 1.0: state

    public static void alEnable(int capability) {
        try {
            H.alEnable.invokeExact(capability);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alDisable(int capability) {
        try {
            H.alDisable.invokeExact(capability);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean alIsEnabled(int capability) {
        return isTrue(H.alIsEnabled, capability);
    }

    public static boolean alGetBoolean(int pname) {
        return isTrue(H.alGetBoolean, pname);
    }

    public static int alGetInteger(int pname) {
        try {
            return (int) H.alGetInteger.invokeExact(pname);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static float alGetFloat(int pname) {
        try {
            return (float) H.alGetFloat.invokeExact(pname);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static double alGetDouble(int pname) {
        try {
            return (double) H.alGetDouble.invokeExact(pname);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alGetInteger(int pname, IntBuffer data) {
        ptr2(H.alGetIntegerv, pname, data, 1, true);
    }

    public static void alGetFloat(int pname, FloatBuffer data) {
        ptr2(H.alGetFloatv, pname, data, 1, true);
    }

    public static void alGetDouble(int pname, DoubleBuffer data) {
        ptr2(H.alGetDoublev, pname, data, 1, true);
    }

    public static String alGetString(int pname) {
        try {
            return string((MemorySegment) H.alGetString.invokeExact(pname));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int alGetError() {
        try {
            return (int) H.alGetError.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean alIsExtensionPresent(String fname) {
        checkNotNull(fname);
        try (Arena a = Arena.ofConfined()) {
            return (byte) H.alIsExtensionPresent.invokeExact(a.allocateFrom(fname)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int alGetEnumValue(String ename) {
        checkNotNull(ename);
        try (Arena a = Arena.ofConfined()) {
            return (int) H.alGetEnumValue.invokeExact(a.allocateFrom(ename));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alDistanceModel(int value) {
        try {
            H.alDistanceModel.invokeExact(value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alDopplerFactor(float value) {
        try {
            H.alDopplerFactor.invokeExact(value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alDopplerVelocity(float value) {
        try {
            H.alDopplerVelocity.invokeExact(value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---------------------------------------------------------------- AL 1.0: listener

    public static void alListeneri(int pname, int value) {
        try {
            H.alListeneri.invokeExact(pname, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alListenerf(int pname, float value) {
        try {
            H.alListenerf.invokeExact(pname, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alListener(int pname, FloatBuffer values) {
        ptr2(H.alListenerfv, pname, values, listenerCount(pname), false);
    }

    public static void alListener3f(int pname, float v1, float v2, float v3) {
        try {
            H.alListener3f.invokeExact(pname, v1, v2, v3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int alGetListeneri(int pname) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT);
            H.alGetListeneri.invokeExact(pname, out);
            return out.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static float alGetListenerf(int pname) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_FLOAT);
            H.alGetListenerf.invokeExact(pname, out);
            return out.get(JAVA_FLOAT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alGetListener(int pname, FloatBuffer values) {
        ptr2(H.alGetListenerfv, pname, values, listenerCount(pname), true);
    }

    // ---------------------------------------------------------------- AL 1.0: sources

    public static void alGenSources(IntBuffer sources) {
        idsOut(H.alGenSources, sources);
    }

    public static int alGenSources() {
        return genOne(H.alGenSources);
    }

    public static void alDeleteSources(IntBuffer sources) {
        idsIn(H.alDeleteSources, sources);
    }

    public static void alDeleteSources(int source) {
        deleteOne(H.alDeleteSources, source);
    }

    public static boolean alIsSource(int id) {
        return isTrue(H.alIsSource, id);
    }

    public static void alSourcei(int source, int pname, int value) {
        set3i(H.alSourcei, source, pname, value);
    }

    public static void alSourcef(int source, int pname, float value) {
        set3f(H.alSourcef, source, pname, value);
    }

    public static void alSource(int source, int pname, FloatBuffer values) {
        ptr3(H.alSourcefv, source, pname, values, sourceCount(pname), false);
    }

    public static void alSource3f(int source, int pname, float v1, float v2, float v3) {
        try {
            H.alSource3f.invokeExact(source, pname, v1, v2, v3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int alGetSourcei(int source, int pname) {
        return outInt3(H.alGetSourcei, source, pname);
    }

    public static float alGetSourcef(int source, int pname) {
        return outFloat3(H.alGetSourcef, source, pname);
    }

    public static void alGetSource(int source, int pname, FloatBuffer values) {
        ptr3(H.alGetSourcefv, source, pname, values, sourceCount(pname), true);
    }

    public static void alSourcePlay(IntBuffer sources) {
        idsIn(H.alSourcePlayv, sources);
    }

    public static void alSourcePause(IntBuffer sources) {
        idsIn(H.alSourcePausev, sources);
    }

    public static void alSourceStop(IntBuffer sources) {
        idsIn(H.alSourceStopv, sources);
    }

    public static void alSourceRewind(IntBuffer sources) {
        idsIn(H.alSourceRewindv, sources);
    }

    public static void alSourcePlay(int source) {
        try {
            H.alSourcePlay.invokeExact(source);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alSourcePause(int source) {
        try {
            H.alSourcePause.invokeExact(source);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alSourceStop(int source) {
        try {
            H.alSourceStop.invokeExact(source);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alSourceRewind(int source) {
        try {
            H.alSourceRewind.invokeExact(source);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alSourceQueueBuffers(int source, IntBuffer buffers) {
        checkNotNull(buffers);
        try (Pin p = new Pin(buffers)) {
            H.alSourceQueueBuffers.invokeExact(source, buffers.remaining(), p.seg);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alSourceQueueBuffers(int source, int buffer) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment in = a.allocate(JAVA_INT);
            in.set(JAVA_INT, 0, buffer);
            H.alSourceQueueBuffers.invokeExact(source, 1, in);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alSourceUnqueueBuffers(int source, IntBuffer buffers) {
        checkNotNull(buffers);
        try (Pin p = new Pin(buffers)) {
            H.alSourceUnqueueBuffers.invokeExact(source, buffers.remaining(), p.seg);
            p.commit();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int alSourceUnqueueBuffers(int source) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT);
            H.alSourceUnqueueBuffers.invokeExact(source, 1, out);
            return out.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---------------------------------------------------------------- AL 1.0: buffers

    public static void alGenBuffers(IntBuffer buffers) {
        idsOut(H.alGenBuffers, buffers);
    }

    public static int alGenBuffers() {
        return genOne(H.alGenBuffers);
    }

    public static void alDeleteBuffers(IntBuffer buffers) {
        idsIn(H.alDeleteBuffers, buffers);
    }

    public static void alDeleteBuffers(int buffer) {
        deleteOne(H.alDeleteBuffers, buffer);
    }

    public static boolean alIsBuffer(int id) {
        return isTrue(H.alIsBuffer, id);
    }

    /** Uploads the remaining bytes of the buffer. */
    public static void alBufferData(int buffer, int format, ByteBuffer data, int frequency) {
        bufferData(buffer, format, data, frequency);
    }

    /** Uploads the remaining ints (4 bytes each) of the buffer. */
    public static void alBufferData(int buffer, int format, IntBuffer data, int frequency) {
        bufferData(buffer, format, data, frequency);
    }

    /** Uploads the remaining shorts (2 bytes each) of the buffer. */
    public static void alBufferData(int buffer, int format, ShortBuffer data, int frequency) {
        bufferData(buffer, format, data, frequency);
    }

    private static void bufferData(int buffer, int format, Buffer data, int frequency) {
        checkNotNull(data);
        try (Pin p = new Pin(data)) {
            long size = p.seg.byteSize();
            if (size > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Buffer too large: " + size + " bytes");
            }
            H.alBufferData.invokeExact(buffer, format, p.seg, (int) size, frequency);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int alGetBufferi(int buffer, int pname) {
        return outInt3(H.alGetBufferi, buffer, pname);
    }

    public static float alGetBufferf(int buffer, int pname) {
        return outFloat3(H.alGetBufferf, buffer, pname);
    }

    // ---------------------------------------------------------------- AL 1.1

    public static void alListener3i(int pname, int v1, int v2, int v3) {
        try {
            H.alListener3i.invokeExact(pname, v1, v2, v3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** LWJGL 2 declares this as a FloatBuffer, but the buffer receives the integer values (alGetListeneriv). */
    public static void alGetListeneri(int pname, FloatBuffer intdata) {
        ptr2(H.alGetListeneriv, pname, intdata, listenerCount(pname), true);
    }

    public static void alSource3i(int source, int pname, int v1, int v2, int v3) {
        try {
            H.alSource3i.invokeExact(source, pname, v1, v2, v3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alSource(int source, int pname, IntBuffer values) {
        ptr3(H.alSourceiv, source, pname, values, sourceCount(pname), false);
    }

    public static void alBufferf(int buffer, int pname, float value) {
        set3f(H.alBufferf, buffer, pname, value);
    }

    public static void alBuffer3f(int buffer, int pname, float v1, float v2, float v3) {
        try {
            H.alBuffer3f.invokeExact(buffer, pname, v1, v2, v3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alBuffer(int buffer, int pname, FloatBuffer values) {
        ptr3(H.alBufferfv, buffer, pname, values, 1, false);
    }

    public static void alBufferi(int buffer, int pname, int value) {
        set3i(H.alBufferi, buffer, pname, value);
    }

    public static void alBuffer3i(int buffer, int pname, int v1, int v2, int v3) {
        try {
            H.alBuffer3i.invokeExact(buffer, pname, v1, v2, v3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void alBuffer(int buffer, int pname, IntBuffer values) {
        ptr3(H.alBufferiv, buffer, pname, values, 1, false);
    }

    public static void alGetBuffer(int buffer, int pname, IntBuffer values) {
        ptr3(H.alGetBufferiv, buffer, pname, values, 1, true);
    }

    public static void alGetBuffer(int buffer, int pname, FloatBuffer values) {
        ptr3(H.alGetBufferfv, buffer, pname, values, 1, true);
    }

    public static void alSpeedOfSound(float value) {
        try {
            H.alSpeedOfSound.invokeExact(value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---------------------------------------------------------------- ALC (raw handles, like the JNI layer of LWJGL 2)

    /** Opens a device by name (UTF-8), null opens the default device; returns the native handle, 0 on failure. */
    public static long alcOpenDevice(String devicename) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment name = devicename == null ? MemorySegment.NULL : a.allocateFrom(devicename);
            return (long) H.alcOpenDevice.invokeExact(name);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** @param devicename address of a NUL-terminated device name, 0 for the default device */
    public static long nalcOpenDevice(long devicename) {
        try {
            return (long) H.alcOpenDevice.invokeExact(MemorySegment.ofAddress(devicename));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean nalcCloseDevice(long device) {
        try {
            return (byte) H.alcCloseDevice.invokeExact(device) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Raw ALC string query. Device lists (queried with device 0: ALC_DEVICE_SPECIFIER, ALC_ALL_DEVICES_SPECIFIER,
     * ALC_CAPTURE_DEVICE_SPECIFIER) come back complete including the terminating double NUL, anything else without its NUL.
     * The buffer is a read-only view of memory owned by OpenAL.
     *
     * @return the string, null if OpenAL returned none
     */
    public static ByteBuffer nalcGetString(long device, int pname) {
        try {
            MemorySegment s = (MemorySegment) H.alcGetString.invokeExact(device, pname);
            if (s.address() == 0) {
                return null;
            }
            boolean list = device == 0 && (pname == ALC_DEVICE_SPECIFIER || pname == ALC_ALL_DEVICES_SPECIFIER
                    || pname == ALC_CAPTURE_DEVICE_SPECIFIER);
            long length = 0;
            if (list) {
                while (s.get(JAVA_BYTE, length) != 0 || s.get(JAVA_BYTE, length + 1) != 0) length++;
                length += 2;
            } else {
                while (s.get(JAVA_BYTE, length) != 0) length++;
            }
            return s.asSlice(0, length).asByteBuffer().asReadOnlyBuffer();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Fills the buffer (remaining() elements are requested) with an ALC integer query. */
    public static void alcGetIntegerv(long device, int pname, IntBuffer values) {
        checkNotNull(values);
        try (Pin p = new Pin(values)) {
            H.alcGetIntegerv.invokeExact(device, pname, values.remaining(), p.seg);
            p.commit();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void nalcGetIntegerv(long device, int pname, int size, long data) {
        try {
            H.alcGetIntegerv.invokeExact(device, pname, size, MemorySegment.ofAddress(data));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Creates a context; the attribute list may be null or a heap buffer. Returns the native handle, 0 on failure. */
    public static long alcCreateContext(long device, IntBuffer attrList) {
        if (attrList == null) {
            return nalcCreateContext(device, 0);
        }
        checkNullTerminated(attrList);
        try (Pin p = new Pin(attrList)) {
            return (long) H.alcCreateContext.invokeExact(device, p.seg);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** @param attrList address of a 0-terminated attribute list, 0 for none */
    public static long nalcCreateContext(long device, long attrList) {
        try {
            return (long) H.alcCreateContext.invokeExact(device, MemorySegment.ofAddress(attrList));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** @return ALC_TRUE (1) or ALC_FALSE (0) */
    public static int nalcMakeContextCurrent(long context) {
        try {
            return (byte) H.alcMakeContextCurrent.invokeExact(context) != 0 ? 1 : 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void nalcProcessContext(long context) {
        try {
            H.alcProcessContext.invokeExact(context);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long nalcGetCurrentContext() {
        try {
            return (long) H.alcGetCurrentContext.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long nalcGetContextsDevice(long context) {
        try {
            return (long) H.alcGetContextsDevice.invokeExact(context);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void nalcSuspendContext(long context) {
        try {
            H.alcSuspendContext.invokeExact(context);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void nalcDestroyContext(long context) {
        try {
            H.alcDestroyContext.invokeExact(context);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int nalcGetError(long device) {
        try {
            return (int) H.alcGetError.invokeExact(device);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean alcIsExtensionPresent(long device, String extName) {
        try (Arena a = Arena.ofConfined()) {
            return (byte) H.alcIsExtensionPresent.invokeExact(device, a.allocateFrom(extName)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean nalcIsExtensionPresent(long device, long extName) {
        try {
            return (byte) H.alcIsExtensionPresent.invokeExact(device, MemorySegment.ofAddress(extName)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int alcGetEnumValue(long device, String enumName) {
        try (Arena a = Arena.ofConfined()) {
            return (int) H.alcGetEnumValue.invokeExact(device, a.allocateFrom(enumName));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int nalcGetEnumValue(long device, long enumName) {
        try {
            return (int) H.alcGetEnumValue.invokeExact(device, MemorySegment.ofAddress(enumName));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---------------------------------------------------------------- ALC 1.1 capture

    /** Opens a capture device by name (UTF-8), null opens the default one; returns the native handle, 0 on failure. */
    public static long alcCaptureOpenDevice(String devicename, int frequency, int format, int buffersize) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment name = devicename == null ? MemorySegment.NULL : a.allocateFrom(devicename);
            return (long) H.alcCaptureOpenDevice.invokeExact(name, frequency, format, buffersize);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long nalcCaptureOpenDevice(long devicename, int frequency, int format, int buffersize) {
        try {
            return (long) H.alcCaptureOpenDevice.invokeExact(MemorySegment.ofAddress(devicename), frequency, format, buffersize);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean nalcCaptureCloseDevice(long device) {
        try {
            return (byte) H.alcCaptureCloseDevice.invokeExact(device) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void nalcCaptureStart(long device) {
        try {
            H.alcCaptureStart.invokeExact(device);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void nalcCaptureStop(long device) {
        try {
            H.alcCaptureStop.invokeExact(device);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Copies captured samples into the buffer (the caller sizes it for {@code samples} frames of the capture format). */
    public static void alcCaptureSamples(long device, ByteBuffer buffer, int samples) {
        checkNotNull(buffer);
        try (Pin p = new Pin(buffer)) {
            H.alcCaptureSamples.invokeExact(device, p.seg, samples);
            p.commit();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void nalcCaptureSamples(long device, long buffer, int samples) {
        try {
            H.alcCaptureSamples.invokeExact(device, MemorySegment.ofAddress(buffer), samples);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---------------------------------------------------------------- EFX 1.0: auxiliary effect slots

    public static void alGenAuxiliaryEffectSlots(IntBuffer auxeffectslots) {
        idsOut(E.SLOT.gen, auxeffectslots);
    }

    public static int alGenAuxiliaryEffectSlots() {
        return genOne(E.SLOT.gen);
    }

    public static void alDeleteAuxiliaryEffectSlots(IntBuffer auxeffectslots) {
        idsIn(E.SLOT.del, auxeffectslots);
    }

    public static void alDeleteAuxiliaryEffectSlots(int auxeffectslot) {
        deleteOne(E.SLOT.del, auxeffectslot);
    }

    public static boolean alIsAuxiliaryEffectSlot(int auxeffectslot) {
        return isTrue(E.SLOT.is, auxeffectslot);
    }

    public static void alAuxiliaryEffectSloti(int auxeffectslot, int param, int value) {
        set3i(E.SLOT.seti, auxeffectslot, param, value);
    }

    public static void alAuxiliaryEffectSlot(int auxeffectslot, int param, IntBuffer values) {
        ptr3(E.SLOT.setiv, auxeffectslot, param, values, 1, false);
    }

    public static void alAuxiliaryEffectSlotf(int auxeffectslot, int param, float value) {
        set3f(E.SLOT.setf, auxeffectslot, param, value);
    }

    public static void alAuxiliaryEffectSlot(int auxeffectslot, int param, FloatBuffer values) {
        ptr3(E.SLOT.setfv, auxeffectslot, param, values, 1, false);
    }

    public static int alGetAuxiliaryEffectSloti(int auxeffectslot, int param) {
        return outInt3(E.SLOT.geti, auxeffectslot, param);
    }

    public static void alGetAuxiliaryEffectSlot(int auxeffectslot, int param, IntBuffer values) {
        ptr3Safe(E.SLOT.getiv, auxeffectslot, param, values, false);
    }

    public static float alGetAuxiliaryEffectSlotf(int auxeffectslot, int param) {
        return outFloat3(E.SLOT.getf, auxeffectslot, param);
    }

    public static void alGetAuxiliaryEffectSlot(int auxeffectslot, int param, FloatBuffer values) {
        ptr3Safe(E.SLOT.getfv, auxeffectslot, param, values, true);
    }

    // ---------------------------------------------------------------- EFX 1.0: effects

    public static void alGenEffects(IntBuffer effects) {
        idsOut(E.EFFECT.gen, effects);
    }

    public static int alGenEffects() {
        return genOne(E.EFFECT.gen);
    }

    public static void alDeleteEffects(IntBuffer effects) {
        idsIn(E.EFFECT.del, effects);
    }

    public static void alDeleteEffects(int effect) {
        deleteOne(E.EFFECT.del, effect);
    }

    public static boolean alIsEffect(int effect) {
        return isTrue(E.EFFECT.is, effect);
    }

    public static void alEffecti(int effect, int param, int value) {
        set3i(E.EFFECT.seti, effect, param, value);
    }

    public static void alEffect(int effect, int param, IntBuffer values) {
        ptr3(E.EFFECT.setiv, effect, param, values, 1, false);
    }

    public static void alEffectf(int effect, int param, float value) {
        set3f(E.EFFECT.setf, effect, param, value);
    }

    public static void alEffect(int effect, int param, FloatBuffer values) {
        ptr3(E.EFFECT.setfv, effect, param, values, 1, false);
    }

    public static int alGetEffecti(int effect, int param) {
        return outInt3(E.EFFECT.geti, effect, param);
    }

    public static void alGetEffect(int effect, int param, IntBuffer values) {
        ptr3Safe(E.EFFECT.getiv, effect, param, values, false);
    }

    public static float alGetEffectf(int effect, int param) {
        return outFloat3(E.EFFECT.getf, effect, param);
    }

    public static void alGetEffect(int effect, int param, FloatBuffer values) {
        ptr3Safe(E.EFFECT.getfv, effect, param, values, true);
    }

    // ---------------------------------------------------------------- EFX 1.0: filters

    public static void alGenFilters(IntBuffer filters) {
        idsOut(E.FILTER.gen, filters);
    }

    public static int alGenFilters() {
        return genOne(E.FILTER.gen);
    }

    public static void alDeleteFilters(IntBuffer filters) {
        idsIn(E.FILTER.del, filters);
    }

    public static void alDeleteFilters(int filter) {
        deleteOne(E.FILTER.del, filter);
    }

    public static boolean alIsFilter(int filter) {
        return isTrue(E.FILTER.is, filter);
    }

    public static void alFilteri(int filter, int param, int value) {
        set3i(E.FILTER.seti, filter, param, value);
    }

    public static void alFilter(int filter, int param, IntBuffer values) {
        ptr3(E.FILTER.setiv, filter, param, values, 1, false);
    }

    public static void alFilterf(int filter, int param, float value) {
        set3f(E.FILTER.setf, filter, param, value);
    }

    public static void alFilter(int filter, int param, FloatBuffer values) {
        ptr3(E.FILTER.setfv, filter, param, values, 1, false);
    }

    public static int alGetFilteri(int filter, int param) {
        return outInt3(E.FILTER.geti, filter, param);
    }

    public static void alGetFilter(int filter, int param, IntBuffer values) {
        ptr3Safe(E.FILTER.getiv, filter, param, values, false);
    }

    public static float alGetFilterf(int filter, int param) {
        return outFloat3(E.FILTER.getf, filter, param);
    }

    public static void alGetFilter(int filter, int param, FloatBuffer values) {
        ptr3Safe(E.FILTER.getfv, filter, param, values, true);
    }
}
