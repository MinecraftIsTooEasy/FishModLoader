package net.xiaoyu233.fml.vulkan.audio;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.TreeSet;

/**
 * Binds the OpenAL functions as FFM downcall handles.
 *
 * <p>Binding never fails: a function the library does not export (or any function when the library could not be loaded at
 * all) becomes a handle that throws {@link UnsupportedOperationException} when it is called, so one missing optional symbol
 * (capture, EFX, a vendor extension) cannot take the whole audio system down. Opaque handles ({@code ALCdevice*},
 * {@code ALCcontext*}) are declared as {@code long}: pointers and 64-bit integers are passed identically by the ABIs of all
 * supported platforms (Windows x64, macOS x64, macOS arm64).
 */
final class AlNatives {
    // layouts of the C types, short names for the descriptors in OpenALCore
    static final ValueLayout.OfInt I = JAVA_INT;
    static final ValueLayout.OfFloat F = JAVA_FLOAT;
    static final ValueLayout.OfDouble D = JAVA_DOUBLE;
    /** ALboolean / ALCboolean results are a C char: only the low byte is meaningful. */
    static final ValueLayout.OfByte B = JAVA_BYTE;
    /** Opaque pointer handle. */
    static final ValueLayout.OfLong L = JAVA_LONG;
    /** Pointer to caller memory. */
    static final AddressLayout P = ADDRESS;
    /** {@code const char*} result: the returned segment spans the whole (NUL terminated) string. */
    static final AddressLayout S = ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle NEW_UNAVAILABLE;
    private static final Set<String> MISSING = new TreeSet<>();
    private static SymbolLookup library;
    private static Throwable libraryFailure;
    private static MethodHandle getProcAddress;
    private static boolean getProcAddressResolved;

    static {
        try {
            NEW_UNAVAILABLE = MethodHandles.lookup().findStatic(AlNatives.class, "newUnavailable",
                    MethodType.methodType(UnsupportedOperationException.class, String.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private AlNatives() {
    }

    static FunctionDescriptor v(MemoryLayout... args) {
        return FunctionDescriptor.ofVoid(args);
    }

    static FunctionDescriptor r(MemoryLayout result, MemoryLayout... args) {
        return FunctionDescriptor.of(result, args);
    }

    /** Binds a core function (looked up directly in the library). */
    static MethodHandle core(String name, FunctionDescriptor descriptor) {
        return bind(name, descriptor, false);
    }

    /** Binds an extension function: exported symbol first, then {@code alGetProcAddress}. */
    static MethodHandle ext(String name, FunctionDescriptor descriptor) {
        return bind(name, descriptor, true);
    }

    /** @return the names of the functions that could not be bound so far (sorted). */
    static synchronized Set<String> missing() {
        return new TreeSet<>(MISSING);
    }

    private static synchronized MethodHandle bind(String name, FunctionDescriptor descriptor, boolean extension) {
        SymbolLookup lib = library();
        if (lib == null) {
            MISSING.add(name);
            return unavailable(name, descriptor, "the OpenAL library could not be loaded: " + libraryFailure);
        }
        MemorySegment address = lib.find(name).orElse(null);
        if (address == null && extension) {
            address = procAddress(lib, name);
        }
        if (address == null) {
            MISSING.add(name);
            return unavailable(name, descriptor, "it is not provided by " + OpenALLibrary.origin());
        }
        return LINKER.downcallHandle(address, descriptor);
    }

    private static SymbolLookup library() {
        if (library == null && libraryFailure == null) {
            try {
                library = OpenALLibrary.lookup();
            } catch (Throwable t) {
                libraryFailure = t;
                AudioLog.warn("OpenAL library not available, every AL/ALC call will fail", t);
            }
        }
        return library;
    }

    /** Resolves an extension function through alGetProcAddress (works without a current context). */
    private static MemorySegment procAddress(SymbolLookup lib, String name) {
        if (!getProcAddressResolved) {
            getProcAddressResolved = true;
            MemorySegment fn = lib.find("alGetProcAddress").orElse(null);
            if (fn != null) {
                getProcAddress = LINKER.downcallHandle(fn, FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            }
        }
        if (getProcAddress == null) {
            return null;
        }
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            long address = (long) getProcAddress.invokeExact(arena.allocateFrom(name));
            return address == 0 ? null : MemorySegment.ofAddress(address);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A handle with the signature of {@code descriptor} that throws UnsupportedOperationException on every call. */
    private static MethodHandle unavailable(String name, FunctionDescriptor descriptor, String reason) {
        MethodType type = descriptor.toMethodType();
        MethodHandle exception = MethodHandles.insertArguments(NEW_UNAVAILABLE, 0, "OpenAL function " + name + " is not available: " + reason);
        MethodHandle thrower = MethodHandles.collectArguments(MethodHandles.throwException(type.returnType(), UnsupportedOperationException.class), 0, exception);
        return MethodHandles.dropArguments(thrower, 0, type.parameterList());
    }

    @SuppressWarnings("unused") // bound through NEW_UNAVAILABLE
    private static UnsupportedOperationException newUnavailable(String message) {
        return new UnsupportedOperationException(message);
    }
}
