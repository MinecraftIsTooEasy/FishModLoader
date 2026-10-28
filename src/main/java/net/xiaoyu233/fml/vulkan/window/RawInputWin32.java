package net.xiaoyu233.fml.vulkan.window;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * WP-31: Windows buffered raw mouse input through Java FFM (user32). Registers a mouse raw-input device on the GLFW window and
 * drains the raw input buffer in batches with {@code GetRawInputBuffer} once per frame (no PeekMessage, GLFW keeps its pump).
 * GetRawInputData needs per-message WM_INPUT handles (owned by GLFW), so there is no per-message fallback here: on any
 * failure the feature disables itself with one warning and the caller keeps its GLFW cursor deltas.
 *
 * <p>Usage (main thread, from the R3 {@code InputManager} raw backend): {@link #start(long)} when the cursor is grabbed,
 * {@link #poll()} each frame, then {@link #takeDeltaX()}/{@link #takeDeltaY()}, {@link #stop()} on ungrab/destroy. While
 * active the backend must not also add GLFW cursor deltas (it would double count).
 *
 * <p>Nothing here runs at class init: all linker lookups happen inside {@link #start(long)}, behind the Windows check.
 */
public final class RawInputWin32 {
    private static final int BUF_SIZE = 8192;

    private static boolean active;
    private static boolean disabled;
    private static long hwnd;
    private static Arena arena;
    private static MemorySegment buffer;
    private static MemorySegment sizeSeg;
    private static MemorySegment deviceSeg;
    private static MethodHandle getBuffer;
    private static MethodHandle register;
    private static final long[] acc = new long[2];
    private static final int PTR = (int) ValueLayout.ADDRESS.byteSize();
    private static long frames;
    private static long events;
    private static long lastLog;

    /** True when the platform, the properties and the earlier self-test allow the buffered backend. */
    public static boolean available() {
        return !disabled && isWindows() && Boolean.parseBoolean(System.getProperty("fml.vulkan.rawBuffered", "true"))
                && VulkanConfig.rawMouse();
    }

    public static boolean isActive() {
        return active;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** Starts buffered raw input for a GLFW window; returns false (and leaves everything untouched) when unavailable. */
    public static boolean start(long glfwWindow) {
        if (active) {
            return true;
        }
        if (!available() || PTR != 8) {
            return false;
        }
        try {
            long h = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
            if (h == 0L) {
                throw new IllegalStateException("no HWND");
            }
            Linker linker = Linker.nativeLinker();
            if (arena == null) {
                arena = Arena.ofConfined();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
                register = linker.downcallHandle(user32.find("RegisterRawInputDevices").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                getBuffer = linker.downcallHandle(user32.find("GetRawInputBuffer").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                buffer = arena.allocate(BUF_SIZE, 8);
                sizeSeg = arena.allocate(4, 4);
                deviceSeg = arena.allocate(RawInputStructs.RAWINPUTDEVICE_SIZE, 8);
            }
            hwnd = h;
            if (!registerDevice(RawInputStructs.RIDEV_INPUTSINK, h)) {
                throw new IllegalStateException("RegisterRawInputDevices failed");
            }
            active = true; // so that drain() runs
            if (drain() < 0) {
                active = false;
                registerDevice(RawInputStructs.RIDEV_REMOVE, 0L);
                throw new IllegalStateException("GetRawInputBuffer returned -1");
            }
            acc[0] = 0;
            acc[1] = 0;
            frames = 0;
            events = 0;
            lastLog = System.nanoTime();
            VkLog.LOGGER.info("raw input buffered: enabled (hwnd 0x{})", Long.toHexString(h));
            return true;
        } catch (Throwable t) {
            active = false;
            disabled = true;
            VkLog.LOGGER.warn("raw input buffered disabled: {}", t.toString());
            return false;
        }
    }

    private static boolean registerDevice(int flags, long target) throws Throwable {
        deviceSeg.set(ValueLayout.JAVA_SHORT, 0, (short) 1);  // usage page: generic desktop
        deviceSeg.set(ValueLayout.JAVA_SHORT, 2, (short) 2);  // usage: mouse
        deviceSeg.set(ValueLayout.JAVA_INT, 4, flags);
        deviceSeg.set(ValueLayout.JAVA_LONG, 8, target);
        return (int) register.invokeExact(deviceSeg, 1, RawInputStructs.RAWINPUTDEVICE_SIZE) != 0;
    }

    /** Drains the raw input buffer once; returns the number of events consumed or -1 on error. */
    private static int drain() {
        int total = 0;
        try {
            for (int guard = 0; guard < 64; guard++) {
                sizeSeg.set(ValueLayout.JAVA_INT, 0, BUF_SIZE);
                int n = (int) getBuffer.invokeExact(buffer, sizeSeg, RawInputStructs.headerSize(PTR));
                if (n < 0) {
                    return total > 0 ? total : -1;
                }
                if (n == 0) {
                    break;
                }
                total += RawInputStructs.decode(buffer, n, PTR, acc);
            }
        } catch (Throwable t) {
            return -1;
        }
        return total;
    }

    /** Per-frame drain (main thread). Accumulates into the deltas read by {@link #takeDeltaX()}. */
    public static void poll() {
        if (!active) {
            return;
        }
        int n = drain();
        if (n < 0) {
            active = false;
            disabled = true;
            VkLog.LOGGER.warn("raw input buffered disabled: GetRawInputBuffer failed at runtime");
            try {
                registerDevice(RawInputStructs.RIDEV_REMOVE, 0L);
            } catch (Throwable ignored) {
            }
            return;
        }
        frames++;
        events += n;
        long now = System.nanoTime();
        if (now - lastLog > 10_000_000_000L) {
            VkLog.LOGGER.info("raw input buffered: {} events/frame", String.format("%.2f", frames == 0 ? 0.0 : (double) events / frames));
            frames = 0;
            events = 0;
            lastLog = now;
        }
    }

    /** Relative motion (device units, right positive) since the last call. */
    public static int takeDeltaX() {
        int v = (int) acc[0];
        acc[0] = 0;
        return v;
    }

    /** Relative motion (device units, down positive, as Windows reports it) since the last call. */
    public static int takeDeltaY() {
        int v = (int) acc[1];
        acc[1] = 0;
        return v;
    }

    /** Unregisters the device (ungrab/destroy). Safe to call when inactive. */
    public static void stop() {
        if (!active) {
            return;
        }
        active = false;
        try {
            drain();
            registerDevice(RawInputStructs.RIDEV_REMOVE, 0L);
        } catch (Throwable ignored) {
        }
        acc[0] = 0;
        acc[1] = 0;
    }

    private RawInputWin32() {
    }
}
