package net.xiaoyu233.fml.vulkan.core;

import net.xiaoyu233.fml.vulkan.VkLog;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImportMetalIOSurfaceInfoEXT;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.vulkan.VK10.*;

/**
 * macOS presentation without CAMetalLayer drawables (vsync off): a pool of IOSurfaces imported as VkImages (VK_EXT_metal_objects), the
 * frame is blitted into a free one and, once its GPU work has completed, handed to the window server as the {@code contents} of an
 * opaque CALayer that covers the CAMetalLayer of the GLFW view (one explicit CATransaction per frame, about 0.01 ms).
 *
 * <p>Why: {@code -[CAMetalLayer nextDrawable]} hands out at most one drawable per display refresh, whatever maximumDrawableCount (2/3),
 * displaySyncEnabled, presentsWithTransaction or the MoltenVK settings say (measured with plain Metal as well as through MoltenVK, see
 * docs/runtime-notes.md), so a CAMetalLayer swapchain presents at most at the refresh rate (120/s here) and vkQueueSubmit /
 * vkAcquireNextImageKHR block in nextDrawable for the rest of the frame. This is the mailbox model Apple's own OpenGL (CGLFlushDrawable)
 * and Chromium use: every frame is handed over, the compositor shows the latest one at its next refresh, nothing blocks.
 *
 * <p>Ownership of a surface: free -> written by the GPU (submitted, until the frame's fence) -> published (shown) -> free again once a newer
 * frame was published and the window server no longer uses it ({@code IOSurfaceIsInUse}). Render thread only (the macOS main thread).
 */
final class LayerPresenter {
    private static final int BGRA = 0x42475241; // 'BGRA'
    private static final long ACQUIRE_TIMEOUT_NANOS = 20_000_000L;
    private static final int MAX_SURFACES = 10;

    // Objective-C / CoreFoundation / IOSurface entry points (resolved once)
    private static boolean resolved;
    private static long msgSend;
    private static long poolPush;
    private static long poolPop;
    private static long ioSurfaceCreate;
    private static long ioSurfaceIsInUse;
    private static long ioSurfaceSetValue;
    private static long kIOSurfaceColorSpace;
    private static long cfRelease;
    private static long cfDictionaryCreateMutable;
    private static long cfDictionarySetValue;
    private static long cfNumberCreate;
    private static long kCFTypeDictionaryKeyCallBacks;
    private static long kCFTypeDictionaryValueCallBacks;
    private static long[] surfaceKeys;
    private static long srgbPropertyList;
    private static long blackColor;
    private static long clsCATransaction;
    private static long clsCALayer;
    private static long selBegin;
    private static long selCommit;
    private static long selFlush;
    private static long selSetDisableActions;
    private static long selSetContents;
    private static long selNew;
    private static long selLayer;
    private static long selAddSublayer;
    private static long selRemoveFromSuperlayer;
    private static long selRelease;
    private static long selSetOpaque;
    private static long selSetBackgroundColor;
    private static long selSetAutoresizingMask;
    private static long selSetFrame;
    private static FFICIF rectCif;

    private final VkDevice device;
    private final long glfwWindow;
    private final long hostLayer;
    private final long layer;
    private int poolSize;
    private final int basePoolSize;

    int width;
    int height;
    long[] images = new long[0];
    private long[] memories = new long[0];
    private long[] surfaces = new long[0];
    private boolean[] gpuBusy = new boolean[0];
    private int shown = -1;
    private int next;
    /** Submitted, not yet published frames in submission order: {surface index, fence}. */
    private final ArrayDeque<long[]> pending = new ArrayDeque<>();
    private long acquireWaits;
    private long acquireSkips;

    private LayerPresenter(VkDevice device, long glfwWindow, long hostLayer, long layer, int poolSize) {
        this.device = device;
        this.glfwWindow = glfwWindow;
        this.hostLayer = hostLayer;
        this.layer = layer;
        this.poolSize = poolSize;
        this.basePoolSize = poolSize;
    }

    /** Creates the presenter for the GLFW window (its view must be backed by the CAMetalLayer of the Vulkan surface); null if unavailable. */
    static LayerPresenter create(VkDevice device, long glfwWindow, int width, int height, int poolSize) {
        try {
            resolve();
            long view = GLFWNativeCocoa.glfwGetCocoaView(glfwWindow);
            long host = view == 0L ? 0L : msgP(view, selLayer);
            if (host == 0L) {
                VkLog.LOGGER.warn("Layer presenter: the GLFW view has no layer, using the swapchain");
                return null;
            }
            long pool = JNI.invokeP(poolPush);
            long sub;
            try {
                sub = msgP(clsCALayer, selNew); // +1, released in destroy
                JNI.invokePPPV(sub, selSetOpaque, 1L, msgSend);
                JNI.invokePPPV(sub, selSetBackgroundColor, blackColor, msgSend);
                JNI.invokePPPV(sub, selSetAutoresizingMask, 18L, msgSend); // kCALayerWidthSizable | kCALayerHeightSizable
                beginTransaction();
                JNI.invokePPPV(host, selAddSublayer, sub, msgSend);
                commitTransaction();
            } finally {
                JNI.invokePV(pool, poolPop);
            }
            LayerPresenter p = new LayerPresenter(device, glfwWindow, host, sub, Math.max(3, poolSize));
            p.resize(width, height);
            return p;
        } catch (Throwable t) {
            VkLog.LOGGER.warn("Layer presenter unavailable, using the CAMetalLayer swapchain (presentation limited to the display refresh)", t);
            return null;
        }
    }

    int poolSize() {
        return poolSize;
    }

    long acquireWaits() {
        return acquireWaits;
    }

    long acquireSkips() {
        return acquireSkips;
    }

    /** (Re)creates the surface pool for a framebuffer size in pixels; the device must be idle. */
    void resize(int w, int h) {
        releaseSurfaces();
        poolSize = basePoolSize; // surfaces added during a live resize are not kept
        width = w;
        height = h;
        images = new long[poolSize];
        memories = new long[poolSize];
        surfaces = new long[poolSize];
        gpuBusy = new boolean[poolSize];
        shown = -1;
        next = 0;
        pending.clear();
        for (int i = 0; i < poolSize; i++) {
            createEntry(i);
        }
        updateFrame();
        VkLog.LOGGER.info("Layer presenter: {}x{}, {} IOSurfaces (CALayer contents, every frame handed to the compositor, no drawable wait)",
                w, h, poolSize);
    }

    /** One more surface (the GPU is idle on the new one, the others keep their state). */
    private void grow() {
        int i = poolSize;
        poolSize++;
        images = java.util.Arrays.copyOf(images, poolSize);
        memories = java.util.Arrays.copyOf(memories, poolSize);
        surfaces = java.util.Arrays.copyOf(surfaces, poolSize);
        gpuBusy = java.util.Arrays.copyOf(gpuBusy, poolSize);
        createEntry(i);
        VkLog.LOGGER.info("Layer presenter: every surface still in use by the window server, pool grown to {}", poolSize);
    }

    private void createEntry(int i) {
        int w = width;
        int h = height;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memProps);
            {
                surfaces[i] = createSurface(w, h);
                VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .pNext(VkImportMetalIOSurfaceInfoEXT.calloc(stack).sType$Default().ioSurface(surfaces[i]).address())
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(VK_FORMAT_B8G8R8A8_UNORM)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                info.extent().set(w, h, 1);
                LongBuffer pHandle = stack.mallocLong(1);
                VkUtil.check(vkCreateImage(device, info, null, pHandle), "vkCreateImage (IOSurface)");
                images[i] = pHandle.get(0);
                VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
                vkGetImageMemoryRequirements(device, images[i], req);
                int type = memoryType(memProps, req.memoryTypeBits());
                // dedicated: MoltenVK then backs the image with the IOSurface only (no placement heap of the image size)
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .pNext(VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(images[i]).address())
                        .allocationSize(req.size())
                        .memoryTypeIndex(type);
                VkUtil.check(vkAllocateMemory(device, alloc, null, pHandle), "vkAllocateMemory (IOSurface image)");
                memories[i] = pHandle.get(0);
                VkUtil.check(vkBindImageMemory(device, images[i], memories[i], 0L), "vkBindImageMemory (IOSurface image)");
                VkUtil.setObjectName(device, VK_OBJECT_TYPE_IMAGE, images[i], "Layer presenter surface " + i);
            }
        }
    }

    /** The layer covers the CAMetalLayer (bounds in points = the window's content size). */
    private void updateFrame() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ww = stack.mallocInt(1);
            IntBuffer wh = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(glfwWindow, ww, wh);
            long pool = JNI.invokeP(poolPush);
            try {
                beginTransaction();
                setRect(layer, selSetFrame, 0.0, 0.0, ww.get(0), wh.get(0));
                commitTransaction();
            } finally {
                JNI.invokePV(pool, poolPop);
            }
        }
    }

    /**
     * A surface the GPU may write now (not in flight, not the shown one, not used by the window server), or -1 after
     * {@value #ACQUIRE_TIMEOUT_NANOS} ns without one (the frame is then not presented). Waiting is rare: the pool holds the frames in
     * flight, the shown frame and two the compositor may still be reading.
     */
    int acquire() {
        long deadline = 0L;
        while (true) {
            for (int n = 0; n < poolSize; n++) {
                int k = (next + n) % poolSize;
                if (!gpuBusy[k] && k != shown && !JNI.invokePZ(surfaces[k], ioSurfaceIsInUse)) {
                    next = k + 1;
                    return k;
                }
            }
            publishCompleted();
            long now = System.nanoTime();
            if (deadline == 0L) {
                deadline = now + ACQUIRE_TIMEOUT_NANOS;
                acquireWaits++;
            } else if (now > deadline && poolSize < MAX_SURFACES && pending.isEmpty()) {
                // the window server holds more surfaces than expected (e.g. during a live resize): add one instead of dropping frames
                grow();
                deadline = 0L;
                continue;
            } else if (now > deadline) {
                acquireSkips++;
                if (acquireSkips <= 5 || acquireSkips % 1000 == 0) {
                    StringBuilder b = new StringBuilder();
                    for (int k = 0; k < poolSize; k++) {
                        b.append(k).append(gpuBusy[k] ? " gpu" : "").append(k == shown ? " shown" : "")
                                .append(JNI.invokePZ(surfaces[k], ioSurfaceIsInUse) ? " inUse" : "").append("; ");
                    }
                    VkLog.LOGGER.warn("Layer presenter: no free surface for {} ms ({} skips so far, {} pending): {}", ACQUIRE_TIMEOUT_NANOS / 1_000_000L,
                            acquireSkips, pending.size(), b);
                }
                return -1;
            }
            LockSupport.parkNanos(20_000L);
        }
    }

    /** The frame writing surface {@code k} was submitted with {@code fence}; it is published once the fence has signalled. */
    void submitted(int k, long fence) {
        gpuBusy[k] = true;
        pending.addLast(new long[]{k, fence});
    }

    /** Publishes, in submission order, every submitted frame whose GPU work has completed (non-blocking). */
    void publishCompleted() {
        long[] head;
        while ((head = pending.peekFirst()) != null && vkGetFenceStatus(device, head[1]) == VK_SUCCESS) {
            pending.pollFirst();
            publish((int) head[0]);
        }
    }

    private void publish(int k) {
        long pool = JNI.invokeP(poolPush);
        try {
            beginTransaction();
            JNI.invokePPPV(layer, selSetContents, surfaces[k], msgSend);
            commitTransaction();
        } finally {
            JNI.invokePV(pool, poolPop);
        }
        gpuBusy[k] = false;
        shown = k;
        RenderCore.notePresent(System.nanoTime());
    }

    /** Releases everything; the device must be idle. The CAMetalLayer below becomes visible again (swapchain path). */
    void destroy() {
        long pool = JNI.invokeP(poolPush);
        try {
            beginTransaction();
            JNI.invokePPPV(layer, selSetContents, 0L, msgSend);
            JNI.invokePPP(layer, selRemoveFromSuperlayer, msgSend);
            commitTransaction();
            JNI.invokePPP(layer, selRelease, msgSend);
        } finally {
            JNI.invokePV(pool, poolPop);
        }
        releaseSurfaces();
    }

    private void releaseSurfaces() {
        pending.clear();
        for (int i = 0; i < images.length; i++) {
            if (images[i] != 0L) {
                vkDestroyImage(device, images[i], null);
            }
            if (memories[i] != 0L) {
                vkFreeMemory(device, memories[i], null);
            }
            if (surfaces[i] != 0L) {
                JNI.invokePV(surfaces[i], cfRelease);
            }
        }
        images = new long[0];
        memories = new long[0];
        surfaces = new long[0];
    }

    private static int memoryType(VkPhysicalDeviceMemoryProperties props, int bits) {
        int any = -1;
        for (int i = 0; i < props.memoryTypeCount(); i++) {
            if ((bits & (1 << i)) == 0) {
                continue;
            }
            if ((props.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                return i;
            }
            if (any < 0) {
                any = i;
            }
        }
        if (any < 0) {
            throw new IllegalStateException("No memory type for an IOSurface image (bits 0x" + Integer.toHexString(bits) + ")");
        }
        return any;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Objective-C / CoreFoundation glue
    // ---------------------------------------------------------------------------------------------------------

    private static long createSurface(int w, int h) {
        long dict = JNI.invokePPPP(0L, 0, kCFTypeDictionaryKeyCallBacks, kCFTypeDictionaryValueCallBacks, cfDictionaryCreateMutable);
        int[] values = {w, h, 4, BGRA};
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < values.length; i++) {
                long number = JNI.invokePPP(0L, 9 /* kCFNumberIntType */, MemoryUtil.memAddress(stack.ints(values[i])), cfNumberCreate);
                JNI.invokePPPV(dict, surfaceKeys[i], number, cfDictionarySetValue);
                JNI.invokePV(number, cfRelease);
            }
        }
        long surface = JNI.invokePP(dict, ioSurfaceCreate);
        JNI.invokePV(dict, cfRelease);
        if (surface == 0L) {
            throw new IllegalStateException("IOSurfaceCreate failed for " + w + "x" + h);
        }
        if (srgbPropertyList != 0L) {
            // the swapchain path tags the CAMetalLayer sRGB (VK_COLOR_SPACE_SRGB_NONLINEAR_KHR): same colours on wide-gamut displays
            JNI.invokePPPV(surface, kIOSurfaceColorSpace, srgbPropertyList, ioSurfaceSetValue);
        }
        return surface;
    }

    private static void beginTransaction() {
        JNI.invokePPP(clsCATransaction, selBegin, msgSend);
        JNI.invokePPPV(clsCATransaction, selSetDisableActions, 1L, msgSend);
    }

    /**
     * Commit plus flush: AppKit (window resize, fullscreen, title...) leaves an implicit transaction open on the main thread, an explicit
     * one is then nested in it and would only reach the window server when the run loop next sleeps, which a polling game loop never does
     * (measured: every surface stayed "in use" for seconds after a resize). The flush costs nothing measurable (0.01 ms per frame).
     */
    private static void commitTransaction() {
        JNI.invokePPP(clsCATransaction, selCommit, msgSend);
        JNI.invokePPP(clsCATransaction, selFlush, msgSend);
    }

    private static long msgP(long receiver, long selector) {
        return JNI.invokePPP(receiver, selector, msgSend);
    }

    /** {@code objc_msgSend(receiver, selector, CGRect)} through libffi (CGRect = four doubles, a homogeneous aggregate on arm64). */
    private static void setRect(long receiver, long selector, double x, double y, double w, double h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pReceiver = stack.pointers(receiver);
            PointerBuffer pSelector = stack.pointers(selector);
            long rect = MemoryUtil.memAddress(stack.doubles(x, y, w, h));
            PointerBuffer values = stack.pointers(pReceiver.address(), pSelector.address(), rect);
            LibFFI.ffi_call(rectCif, msgSend, null, values);
        }
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        SharedLibrary objc = ObjCRuntime.getLibrary();
        msgSend = need(objc.getFunctionAddress("objc_msgSend"), "objc_msgSend");
        poolPush = need(objc.getFunctionAddress("objc_autoreleasePoolPush"), "objc_autoreleasePoolPush");
        poolPop = need(objc.getFunctionAddress("objc_autoreleasePoolPop"), "objc_autoreleasePoolPop");
        SharedLibrary io = Library.loadNative(LayerPresenter.class, "org.lwjgl", "/System/Library/Frameworks/IOSurface.framework/IOSurface");
        SharedLibrary cf = Library.loadNative(LayerPresenter.class, "org.lwjgl", "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
        SharedLibrary cg = Library.loadNative(LayerPresenter.class, "org.lwjgl", "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");
        Library.loadNative(LayerPresenter.class, "org.lwjgl", "/System/Library/Frameworks/QuartzCore.framework/QuartzCore"); // CALayer, CATransaction
        ioSurfaceCreate = need(io.getFunctionAddress("IOSurfaceCreate"), "IOSurfaceCreate");
        ioSurfaceIsInUse = need(io.getFunctionAddress("IOSurfaceIsInUse"), "IOSurfaceIsInUse");
        ioSurfaceSetValue = need(io.getFunctionAddress("IOSurfaceSetValue"), "IOSurfaceSetValue");
        String[] keys = {"kIOSurfaceWidth", "kIOSurfaceHeight", "kIOSurfaceBytesPerElement", "kIOSurfacePixelFormat"};
        surfaceKeys = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            surfaceKeys[i] = MemoryUtil.memGetAddress(need(io.getFunctionAddress(keys[i]), keys[i]));
        }
        kIOSurfaceColorSpace = MemoryUtil.memGetAddress(need(io.getFunctionAddress("kIOSurfaceColorSpace"), "kIOSurfaceColorSpace"));
        cfRelease = need(cf.getFunctionAddress("CFRelease"), "CFRelease");
        cfDictionaryCreateMutable = need(cf.getFunctionAddress("CFDictionaryCreateMutable"), "CFDictionaryCreateMutable");
        cfDictionarySetValue = need(cf.getFunctionAddress("CFDictionarySetValue"), "CFDictionarySetValue");
        cfNumberCreate = need(cf.getFunctionAddress("CFNumberCreate"), "CFNumberCreate");
        kCFTypeDictionaryKeyCallBacks = need(cf.getFunctionAddress("kCFTypeDictionaryKeyCallBacks"), "kCFTypeDictionaryKeyCallBacks");
        kCFTypeDictionaryValueCallBacks = need(cf.getFunctionAddress("kCFTypeDictionaryValueCallBacks"), "kCFTypeDictionaryValueCallBacks");
        long srgbName = MemoryUtil.memGetAddress(need(cg.getFunctionAddress("kCGColorSpaceSRGB"), "kCGColorSpaceSRGB"));
        long srgb = JNI.invokePP(srgbName, need(cg.getFunctionAddress("CGColorSpaceCreateWithName"), "CGColorSpaceCreateWithName"));
        srgbPropertyList = srgb == 0L ? 0L : JNI.invokePP(srgb, need(cg.getFunctionAddress("CGColorSpaceCopyPropertyList"), "CGColorSpaceCopyPropertyList"));
        long blackName = MemoryUtil.memGetAddress(need(cg.getFunctionAddress("kCGColorBlack"), "kCGColorBlack"));
        blackColor = JNI.invokePP(blackName, need(cg.getFunctionAddress("CGColorGetConstantColor"), "CGColorGetConstantColor"));
        clsCATransaction = need(ObjCRuntime.objc_getClass("CATransaction"), "CATransaction");
        clsCALayer = need(ObjCRuntime.objc_getClass("CALayer"), "CALayer");
        selBegin = ObjCRuntime.sel_getUid("begin");
        selCommit = ObjCRuntime.sel_getUid("commit");
        selFlush = ObjCRuntime.sel_getUid("flush");
        selSetDisableActions = ObjCRuntime.sel_getUid("setDisableActions:");
        selSetContents = ObjCRuntime.sel_getUid("setContents:");
        selNew = ObjCRuntime.sel_getUid("new");
        selLayer = ObjCRuntime.sel_getUid("layer");
        selAddSublayer = ObjCRuntime.sel_getUid("addSublayer:");
        selRemoveFromSuperlayer = ObjCRuntime.sel_getUid("removeFromSuperlayer");
        selRelease = ObjCRuntime.sel_getUid("release");
        selSetOpaque = ObjCRuntime.sel_getUid("setOpaque:");
        selSetBackgroundColor = ObjCRuntime.sel_getUid("setBackgroundColor:");
        selSetAutoresizingMask = ObjCRuntime.sel_getUid("setAutoresizingMask:");
        selSetFrame = ObjCRuntime.sel_getUid("setFrame:");

        // libffi call interface for (id, SEL, CGRect) -> void; allocated once for the process lifetime
        FFIType rect = FFIType.calloc().type((short) LibFFI.FFI_TYPE_STRUCT);
        PointerBuffer elements = MemoryUtil.memAllocPointer(5);
        for (int i = 0; i < 4; i++) {
            elements.put(i, LibFFI.ffi_type_double);
        }
        elements.put(4, 0L);
        rect.elements(elements);
        PointerBuffer args = MemoryUtil.memAllocPointer(3);
        args.put(0, LibFFI.ffi_type_pointer).put(1, LibFFI.ffi_type_pointer).put(2, rect);
        rectCif = FFICIF.calloc();
        int status = LibFFI.ffi_prep_cif(rectCif, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_void, args);
        if (status != LibFFI.FFI_OK) {
            throw new IllegalStateException("ffi_prep_cif(CGRect) failed: " + status);
        }
        resolved = true;
    }

    private static long need(long address, String what) {
        if (address == 0L) {
            throw new IllegalStateException(what + " not found");
        }
        return address;
    }
}
