package net.xiaoyu233.fml.vulkan.core;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRPortabilityEnumeration;
import org.lwjgl.vulkan.KHRPortabilitySubset;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXTI;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDevicePortabilitySubsetFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Instance / surface / physical device / logical device / VMA creation for {@link RenderCore}. Every enumeration runs in its own
 * short MemoryStack frame (large property arrays live on the native heap), so the 64 KiB thread stack is never the limit even
 * on drivers that expose 200+ device extensions.
 */
final class DeviceSetup {
    private static final int MAX_API_VERSION = VK13.VK_API_VERSION_1_3;
    /**
     * A DEVICE_LOCAL | HOST_VISIBLE heap smaller than this that sits next to a bigger device-local heap is a CPU-visible window of the
     * video memory (e.g. 256 MiB "visible VRAM" of an AMD GPU / APU), not unified memory.
     */
    private static final long UNIFIED_HEAP_MIN_BYTES = 1L << 30;

    private DeviceSetup() {
    }

    /** Everything this class creates; {@link #destroy} releases whatever exists, in reverse creation order. */
    static final class Context {
        VkInstance instance;
        int instanceApiVersion;
        boolean debugUtils;
        long debugMessenger;
        VkDebugUtilsMessengerCallbackEXT debugCallback;

        long surface;

        VkPhysicalDevice physicalDevice;
        int queueFamily;
        VkDevice device;
        VkQueue queue;
        Set<String> deviceExtensions = Set.of();
        int deviceApiVersion;

        long allocator;

        DeviceInfo info;
        /**
         * Unified memory: an integrated / Apple GPU whose DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT memory is (about) all of its memory
         * (see {@link DeviceSetup#isUnifiedMemory}), so static buffers can be written directly instead of being staged.
         */
        boolean uma;
        /** Largest framebuffer / image edge we create (min of maxImageDimension2D and maxFramebufferWidth/Height). */
        int maxTargetDimension;
    }

    private record Candidate(VkPhysicalDevice device, String name, int type, int vendorId, int queueFamily, long score,
                             String rejectReason, int apiVersion) {
        boolean usable() {
            return rejectReason == null;
        }
    }

    static Context create(long glfwWindow) {
        Context c = new Context();
        try {
            createInstance(c);
            createSurface(c, glfwWindow);
            selectPhysicalDevice(c);
            createDevice(c);
            createAllocator(c);
            return c;
        } catch (Throwable t) {
            destroy(c);
            throw t;
        }
    }

    /** Null/zero safe; destroys in reverse creation order. The caller has already destroyed everything created on the device. */
    static void destroy(Context c) {
        if (c == null) {
            return;
        }
        if (c.allocator != 0) {
            try {
                Vma.vmaDestroyAllocator(c.allocator);
            } catch (Throwable t) {
                VkLog.LOGGER.warn("vmaDestroyAllocator failed", t);
            }
            c.allocator = 0;
        }
        if (c.device != null) {
            try {
                vkDestroyDevice(c.device, null);
            } catch (Throwable t) {
                VkLog.LOGGER.warn("vkDestroyDevice failed", t);
            }
            c.device = null;
            c.queue = null;
        }
        if (c.surface != 0 && c.instance != null) {
            try {
                KHRSurface.vkDestroySurfaceKHR(c.instance, c.surface, null);
            } catch (Throwable t) {
                VkLog.LOGGER.warn("vkDestroySurfaceKHR failed", t);
            }
            c.surface = 0;
        }
        if (c.debugMessenger != 0 && c.instance != null) {
            try {
                EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(c.instance, c.debugMessenger, null);
            } catch (Throwable t) {
                VkLog.LOGGER.warn("vkDestroyDebugUtilsMessengerEXT failed", t);
            }
            c.debugMessenger = 0;
        }
        if (c.instance != null) {
            try {
                vkDestroyInstance(c.instance, null);
            } catch (Throwable t) {
                VkLog.LOGGER.warn("vkDestroyInstance failed", t);
            }
            c.instance = null;
        }
        if (c.debugCallback != null) {
            c.debugCallback.free();
            c.debugCallback = null;
        }
        VkUtil.debugUtils = false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // instance
    // ---------------------------------------------------------------------------------------------------------

    /**
     * LWJGL's VK class initialiser creates the Vulkan loader on first use (org.lwjgl.vulkan.libname = the bundled MoltenVK on macOS),
     * which usually happened in module E already; calling VK.create() a second time throws. Only with org.lwjgl.vulkan.explicitInit
     * is the loader missing, and getFunctionProvider() then throws instead of returning null.
     */
    private static void ensureVulkanLoader() {
        try {
            VK.getFunctionProvider();
        } catch (IllegalStateException notLoaded) {
            VK.create();
        }
    }

    private static void createInstance(Context c) {
        ensureVulkanLoader();
        int supported = VK.getInstanceVersionSupported();
        if ((supported & ~0xFFF) < VK11.VK_API_VERSION_1_1) {
            throw new IllegalStateException("Vulkan 1.1 or later is required, but the Vulkan loader only supports "
                    + versionString(supported) + ". Please update the graphics driver / Vulkan runtime.");
        }
        c.instanceApiVersion = Math.min(MAX_API_VERSION, supported & ~0xFFF);

        PointerBuffer glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            throw new IllegalStateException("GLFW cannot create Vulkan window surfaces (glfwGetRequiredInstanceExtensions returned null): "
                    + "the Vulkan loader or the graphics driver is missing or does not support presentation to a window.");
        }
        Set<String> extensions = new LinkedHashSet<>();
        for (int i = 0; i < glfwExtensions.remaining(); i++) {
            extensions.add(MemoryUtil.memUTF8(glfwExtensions.get(glfwExtensions.position() + i)));
        }

        Set<String> available = enumerateInstanceExtensions();
        for (String required : extensions) {
            if (!available.contains(required)) {
                throw new IllegalStateException("The Vulkan instance extension " + required + " required by GLFW is not available "
                        + "(available: " + available + ")");
            }
        }
        boolean portabilityEnumeration = available.contains(KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
        if (portabilityEnumeration) {
            extensions.add(KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
        }
        if (available.contains("VK_KHR_get_physical_device_properties2")) {
            extensions.add("VK_KHR_get_physical_device_properties2");
        }

        boolean validation = VulkanConfig.validation();
        boolean wantMessenger = validation || VulkanConfig.debug();
        c.debugUtils = wantMessenger && available.contains(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        if (c.debugUtils) {
            extensions.add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        } else if (wantMessenger) {
            VkLog.LOGGER.warn("VK_EXT_debug_utils is not available: no Vulkan debug messages / object names");
        }

        boolean enableValidationLayer = false;
        if (validation) {
            if (enumerateInstanceLayers().contains("VK_LAYER_KHRONOS_validation")) {
                enableValidationLayer = true;
            } else {
                VkLog.LOGGER.warn("fml.vulkan.validation is set but VK_LAYER_KHRONOS_validation was not found, continuing without "
                        + "validation. {}", validationLayerHint());
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("FishModLoader"))
                    .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("FML Vulkan"))
                    .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(c.instanceApiVersion);

            PointerBuffer ppExtensions = stack.mallocPointer(extensions.size());
            for (String extension : extensions) {
                ppExtensions.put(stack.UTF8(extension));
            }
            ppExtensions.flip();

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(ppExtensions);
            if (portabilityEnumeration) {
                createInfo.flags(KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR);
            }
            if (enableValidationLayer) {
                createInfo.ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")));
            }

            VkDebugUtilsMessengerCreateInfoEXT messengerInfo = null;
            if (c.debugUtils) {
                c.debugCallback = VkDebugUtilsMessengerCallbackEXT.create(new DebugCallback());
                messengerInfo = messengerCreateInfo(stack, c.debugCallback);
                createInfo.pNext(messengerInfo); // also reports problems of vkCreateInstance / vkDestroyInstance
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, pInstance);
            if (result == VK_ERROR_INCOMPATIBLE_DRIVER) {
                throw new IllegalStateException("No Vulkan 1.1 driver found (VK_ERROR_INCOMPATIBLE_DRIVER). "
                        + "Please install / update the graphics driver.");
            }
            VkUtil.check(result, "vkCreateInstance");
            c.instance = new VkInstance(pInstance.get(0), createInfo);

            if (c.debugUtils) {
                LongBuffer pMessenger = stack.mallocLong(1);
                VkUtil.check(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(c.instance, messengerInfo, null, pMessenger),
                        "vkCreateDebugUtilsMessengerEXT");
                c.debugMessenger = pMessenger.get(0);
                VkUtil.debugUtils = true;
            }
        }
        VkLog.LOGGER.info("Vulkan instance created (API {}, loader supports {}{}{})", versionString(c.instanceApiVersion),
                versionString(supported), enableValidationLayer ? ", validation layer on" : "", c.debugUtils ? ", debug utils on" : "");
    }

    /** How to get the validation layer; on macOS the default (bundled MoltenVK, loaded directly) has no layer mechanism at all. */
    private static String validationLayerHint() {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        String library = System.getProperty("org.lwjgl.vulkan.libname");
        if (mac && (library == null || library.toLowerCase(Locale.ROOT).contains("moltenvk"))) {
            return "On macOS the bundled MoltenVK is loaded directly (not through the Vulkan loader), so installing the Vulkan SDK alone does "
                    + "not enable any layer. Install the LunarG Vulkan SDK, start the game with "
                    + "-Dfml.vulkan.library=<VulkanSDK>/macOS/lib/libvulkan.1.dylib and set the environment variables "
                    + "VK_ICD_FILENAMES=<VulkanSDK>/macOS/share/vulkan/icd.d/MoltenVK_icd.json and "
                    + "VK_ADD_LAYER_PATH=<VulkanSDK>/macOS/share/vulkan/explicit_layer.d";
        }
        return "Install the Vulkan SDK (or the validation layers package of the distribution) and make sure the loader finds the layer "
                + "manifest (VK_LAYER_PATH / VK_ADD_LAYER_PATH).";
    }

    private static VkDebugUtilsMessengerCreateInfoEXT messengerCreateInfo(MemoryStack stack, VkDebugUtilsMessengerCallbackEXT callback) {
        int severity = EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
        if (VulkanConfig.debug()) {
            severity |= EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT;
        }
        return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(severity)
                .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                        | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(callback);
    }

    /** Forwards VK_EXT_debug_utils messages (validation layer, loader, MoltenVK) to {@link VkLog}. */
    private static final class DebugCallback implements VkDebugUtilsMessengerCallbackEXTI {
        @Override
        public int invoke(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
            try {
                VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                String id = data.pMessageIdNameString();
                String message = "[Vulkan" + (id != null ? " " + id : "") + "] " + data.pMessageString();
                if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                    VkLog.LOGGER.error(message);
                } else if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                    VkLog.LOGGER.warn(message);
                } else if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) {
                    VkLog.LOGGER.info(message);
                } else {
                    VkLog.LOGGER.debug(message);
                }
            } catch (Throwable t) {
                // never let an exception escape into native code
            }
            return VK_FALSE;
        }
    }

    private static Set<String> enumerateInstanceExtensions() {
        Set<String> result = new HashSet<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            for (int attempt = 0; attempt < 4; attempt++) {
                VkUtil.check(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pCount, null), "vkEnumerateInstanceExtensionProperties");
                int count = pCount.get(0);
                if (count == 0) {
                    return result;
                }
                VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count); // native heap, not the 64 KiB stack
                try {
                    pCount.put(0, count);
                    int r = vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pCount, props);
                    VkUtil.check(r, "vkEnumerateInstanceExtensionProperties");
                    if (r == VK_INCOMPLETE) {
                        continue; // the list grew in between
                    }
                    for (int i = 0; i < pCount.get(0); i++) {
                        result.add(props.get(i).extensionNameString());
                    }
                    return result;
                } finally {
                    props.free();
                }
            }
        }
        return result;
    }

    private static Set<String> enumerateInstanceLayers() {
        Set<String> result = new HashSet<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            for (int attempt = 0; attempt < 4; attempt++) {
                VkUtil.check(vkEnumerateInstanceLayerProperties(pCount, null), "vkEnumerateInstanceLayerProperties");
                int count = pCount.get(0);
                if (count == 0) {
                    return result;
                }
                VkLayerProperties.Buffer props = VkLayerProperties.malloc(count);
                try {
                    pCount.put(0, count);
                    int r = vkEnumerateInstanceLayerProperties(pCount, props);
                    VkUtil.check(r, "vkEnumerateInstanceLayerProperties");
                    if (r == VK_INCOMPLETE) {
                        continue;
                    }
                    for (int i = 0; i < pCount.get(0); i++) {
                        result.add(props.get(i).layerNameString());
                    }
                    return result;
                } finally {
                    props.free();
                }
            }
        }
        return result;
    }

    private static Set<String> enumerateDeviceExtensions(VkPhysicalDevice device) {
        Set<String> result = new HashSet<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            for (int attempt = 0; attempt < 4; attempt++) {
                VkUtil.check(vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, pCount, null), "vkEnumerateDeviceExtensionProperties");
                int count = pCount.get(0);
                if (count == 0) {
                    return result;
                }
                VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count); // 200+ entries x 260 B would not fit the stack
                try {
                    pCount.put(0, count);
                    int r = vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, pCount, props);
                    VkUtil.check(r, "vkEnumerateDeviceExtensionProperties");
                    if (r == VK_INCOMPLETE) {
                        continue;
                    }
                    for (int i = 0; i < pCount.get(0); i++) {
                        result.add(props.get(i).extensionNameString());
                    }
                    return result;
                } finally {
                    props.free();
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------------------
    // surface
    // ---------------------------------------------------------------------------------------------------------

    private static void createSurface(Context c, long glfwWindow) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            VkUtil.check(GLFWVulkan.glfwCreateWindowSurface(c.instance, glfwWindow, null, pSurface), "glfwCreateWindowSurface");
            c.surface = pSurface.get(0);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // physical device
    // ---------------------------------------------------------------------------------------------------------

    private static void selectPhysicalDevice(Context c) {
        List<VkPhysicalDevice> devices = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            VkUtil.check(vkEnumeratePhysicalDevices(c.instance, pCount, null), "vkEnumeratePhysicalDevices");
            int count = pCount.get(0);
            if (count == 0) {
                throw new IllegalStateException("No Vulkan physical device found. Please install / update the graphics driver "
                        + "(Vulkan 1.1 capable GPU required).");
            }
            PointerBuffer pDevices = stack.mallocPointer(count);
            VkUtil.check(vkEnumeratePhysicalDevices(c.instance, pCount, pDevices), "vkEnumeratePhysicalDevices");
            for (int i = 0; i < pCount.get(0); i++) {
                devices.add(new VkPhysicalDevice(pDevices.get(i), c.instance));
            }
        }

        Candidate best = null;
        List<String> rejected = new ArrayList<>();
        for (VkPhysicalDevice device : devices) {
            Candidate candidate = evaluate(c, device);
            if (candidate.usable()) {
                VkLog.LOGGER.debug("Vulkan device candidate: {} (type {}, score {})", candidate.name(), candidate.type(), candidate.score());
                if (best == null || candidate.score() > best.score()) {
                    best = candidate;
                }
            } else {
                VkLog.LOGGER.info("Vulkan device {} rejected: {}", candidate.name(), candidate.rejectReason());
                rejected.add(candidate.name() + ": " + candidate.rejectReason());
            }
        }
        if (best == null) {
            throw new IllegalStateException("No suitable Vulkan device found. Rejected devices: " + rejected);
        }
        c.physicalDevice = best.device();
        c.queueFamily = best.queueFamily();
        c.deviceApiVersion = best.apiVersion();
        c.deviceExtensions = enumerateDeviceExtensions(best.device());
        queryDeviceInfo(c);
    }

    private static Candidate evaluate(Context c, VkPhysicalDevice device) {
        String name;
        int type;
        int vendor;
        int apiVersion;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device, props);
            name = props.deviceNameString();
            type = props.deviceType();
            vendor = props.vendorID();
            apiVersion = props.apiVersion();
        }
        if ((apiVersion & ~0xFFF) < VK11.VK_API_VERSION_1_1) {
            return reject(name, type, vendor, apiVersion, "Vulkan 1.1 required, device supports " + versionString(apiVersion));
        }
        if (!enumerateDeviceExtensions(device).contains(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
            return reject(name, type, vendor, apiVersion, "VK_KHR_swapchain not supported");
        }

        int queueFamily = -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device, pCount, null);
            int familyCount = pCount.get(0);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(familyCount, stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, pCount, families);
            IntBuffer pSupported = stack.mallocInt(1);
            for (int i = 0; i < pCount.get(0); i++) {
                if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0 || families.get(i).queueCount() < 1) {
                    continue;
                }
                int r = KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(device, i, c.surface, pSupported);
                if (r != VK_SUCCESS) {
                    VkLog.LOGGER.warn("vkGetPhysicalDeviceSurfaceSupportKHR({}, family {}) failed: {}", name, i, VkUtil.resultName(r));
                    continue;
                }
                if (pSupported.get(0) != VK_FALSE) {
                    queueFamily = i;
                    break;
                }
            }
        }
        if (queueFamily < 0) {
            return reject(name, type, vendor, apiVersion, "no queue family with graphics + presentation support for the window surface");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            int r = KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device, c.surface, pCount, null);
            if (r != VK_SUCCESS || pCount.get(0) == 0) {
                return reject(name, type, vendor, apiVersion, "no surface formats (" + VkUtil.resultName(r) + ")");
            }
            r = KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device, c.surface, pCount, null);
            if (r != VK_SUCCESS || pCount.get(0) == 0) {
                return reject(name, type, vendor, apiVersion, "no present modes (" + VkUtil.resultName(r) + ")");
            }
        }

        if (chooseColorFormat(device) == 0) {
            return reject(name, type, vendor, apiVersion, "neither R8G8B8A8_UNORM nor B8G8R8A8_UNORM supports colour attachment, "
                    + "blending, blit and transfer");
        }
        if (chooseDepthFormat(device) == 0) {
            return reject(name, type, vendor, apiVersion, "no depth attachment format (D32_SFLOAT / D24_UNORM_S8_UINT / D16_UNORM)");
        }

        long score = switch (type) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 4_000_000L;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 3_000_000L;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 2_000_000L;
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> 1_000_000L;
            default -> 0L;
        };
        score += Math.min(deviceLocalHeapMiB(device), 999_999L);
        return new Candidate(device, name, type, vendor, queueFamily, score, null, apiVersion);
    }

    private static Candidate reject(String name, int type, int vendor, int apiVersion, String reason) {
        return new Candidate(null, name, type, vendor, -1, -1, reason, apiVersion);
    }

    private static long deviceLocalHeapMiB(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(device, mem);
            return largestDeviceLocalHeapBytes(mem) >> 20;
        }
    }

    /** Size of the biggest heap with VK_MEMORY_HEAP_DEVICE_LOCAL_BIT; 0 when there is none. */
    private static long largestDeviceLocalHeapBytes(VkPhysicalDeviceMemoryProperties mem) {
        long best = 0;
        for (int i = 0; i < mem.memoryHeapCount(); i++) {
            if ((mem.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                best = Math.max(best, mem.memoryHeaps(i).size());
            }
        }
        return best;
    }

    /**
     * Whether a DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT memory type really is the GPU's memory (unified / shared memory) and not just
     * a CPU-visible window of it. Apple and Intel integrated GPUs have one big heap that holds all their memory types: the unified heap
     * is the largest device-local heap. An AMD GPU / APU on Windows lists such a type in a small (e.g. 256 MiB) heap next to a
     * multi-GiB device-local heap whose types are not host visible: writing all static buffers into that window would exhaust it.
     * A unified heap that is not the largest one still qualifies when it is a big part (at least half) of it and at least 1 GiB.
     *
     * @param unifiedHeapBytes            size of the biggest heap that has such a memory type; 0 when no type has these properties
     * @param largestDeviceLocalHeapBytes size of the biggest DEVICE_LOCAL heap
     */
    static boolean isUnifiedMemory(long unifiedHeapBytes, long largestDeviceLocalHeapBytes) {
        if (unifiedHeapBytes <= 0) {
            return false;
        }
        return unifiedHeapBytes >= largestDeviceLocalHeapBytes
                || (unifiedHeapBytes >= UNIFIED_HEAP_MIN_BYTES && unifiedHeapBytes >= (largestDeviceLocalHeapBytes + 1) / 2);
    }

    /**
     * R8G8B8A8_UNORM (preferred) or B8G8R8A8_UNORM for the offscreen "default framebuffer": needs colour attachment + blending,
     * blit source/destination (swapchain present, glCopyTexSubImage2D) and transfer source (readback, capture). 0 when none fits.
     */
    static int chooseColorFormat(VkPhysicalDevice device) {
        int required = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT
                | VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
        for (int format : new int[]{VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM}) {
            if (optimalFeatures(device, format, required) ) {
                return format;
            }
        }
        return 0;
    }

    /** First of D32_SFLOAT, D24_UNORM_S8_UINT, D16_UNORM usable as depth attachment; 0 when none. */
    static int chooseDepthFormat(VkPhysicalDevice device) {
        for (int format : new int[]{VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D16_UNORM}) {
            if (optimalFeatures(device, format, VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)) {
                return format;
            }
        }
        return 0;
    }

    static boolean hasStencil(int depthFormat) {
        return depthFormat == VK_FORMAT_D24_UNORM_S8_UINT || depthFormat == VK_FORMAT_D32_SFLOAT_S8_UINT
                || depthFormat == VK_FORMAT_D16_UNORM_S8_UINT;
    }

    static boolean optimalFeatures(VkPhysicalDevice device, int format, int requiredFeatures) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(device, format, props);
            return (props.optimalTilingFeatures() & requiredFeatures) == requiredFeatures;
        }
    }

    private static void queryDeviceInfo(Context c) {
        VkPhysicalDevice pd = c.physicalDevice;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(pd, props);
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(pd, features);

            // VK_KHR_portability_subset (MoltenVK) devices lack some features that are mandatory elsewhere; createDevice enables every one
            // the device offers. A device without the extension has them all.
            boolean portabilitySwizzle = true;
            boolean portabilityLodBias = true;
            if (c.deviceExtensions.contains(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)) {
                VkPhysicalDevicePortabilitySubsetFeaturesKHR portability = queryPortabilityFeatures(pd, stack);
                portabilitySwizzle = portability.imageViewFormatSwizzle();
                portabilityLodBias = portability.samplerMipLodBias();
            }

            String driverName = null;
            String driverInfoText = null;
            int driverId = 0;
            if ((props.apiVersion() & ~0xFFF) >= VK12.VK_API_VERSION_1_2 || c.deviceExtensions.contains("VK_KHR_driver_properties")) {
                VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(driver.address());
                VK11.vkGetPhysicalDeviceProperties2(pd, props2);
                driverName = driver.driverNameString();
                driverInfoText = driver.driverInfoString();
                driverId = driver.driverID();
            }

            String driverInfo;
            if (driverName != null && !driverName.isBlank()) {
                driverInfo = driverName.trim() + (driverInfoText != null && !driverInfoText.isBlank() ? " " + driverInfoText.trim() : "");
            } else {
                driverInfo = "driver 0x" + Integer.toHexString(props.driverVersion());
            }
            boolean moltenVk = driverId == VK12.VK_DRIVER_ID_MOLTENVK
                    || (driverName != null && driverName.toLowerCase(Locale.ROOT).contains("moltenvk"))
                    || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

            c.info = new DeviceInfo(
                    vendorName(props.vendorID()),
                    props.deviceNameString(),
                    driverInfo,
                    props.apiVersion(),
                    moltenVk,
                    features.wideLines(),
                    features.fillModeNonSolid(),
                    features.samplerAnisotropy(),
                    props.limits().maxSamplerAnisotropy(),
                    features.logicOp(),
                    props.limits().maxImageDimension2D(),
                    props.limits().minUniformBufferOffsetAlignment(),
                    new float[]{props.limits().lineWidthRange(0), props.limits().lineWidthRange(1)},
                    new float[]{props.limits().pointSizeRange(0), props.limits().pointSizeRange(1)},
                    portabilitySwizzle,
                    portabilityLodBias);

            c.maxTargetDimension = Math.max(1, Math.min(props.limits().maxImageDimension2D(),
                    Math.min(props.limits().maxFramebufferWidth(), props.limits().maxFramebufferHeight())));

            // unified memory: integrated / Apple GPUs whose DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT memory is all of their memory
            boolean integrated = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU || props.vendorID() == 0x106B;
            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(pd, mem);
            int unified = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            long unifiedHeapBytes = 0;
            for (int i = 0; i < mem.memoryTypeCount(); i++) {
                if ((mem.memoryTypes(i).propertyFlags() & unified) == unified) {
                    unifiedHeapBytes = Math.max(unifiedHeapBytes, mem.memoryHeaps(mem.memoryTypes(i).heapIndex()).size());
                }
            }
            long deviceLocalHeapBytes = largestDeviceLocalHeapBytes(mem);
            c.uma = integrated && isUnifiedMemory(unifiedHeapBytes, deviceLocalHeapBytes);
            if (integrated && unifiedHeapBytes > 0 && !c.uma) {
                VkLog.LOGGER.info("Static buffers are staged into device-local memory: the host-visible device-local heap is only {} MiB, "
                        + "the largest device-local heap {} MiB (not unified memory)", unifiedHeapBytes >> 20, deviceLocalHeapBytes >> 20);
            }

            VkLog.LOGGER.info("Vulkan device: {} ({}), API {}, {}, queue family {}{}{}", c.info.deviceName(), c.info.vendorName(),
                    versionString(props.apiVersion()), driverInfo, c.queueFamily, c.uma ? ", unified memory" : "",
                    moltenVk ? ", MoltenVK" : "");
        }
    }

    /** The VK_KHR_portability_subset features the device supports (vkGetPhysicalDeviceFeatures2); only valid when the extension is exposed. */
    private static VkPhysicalDevicePortabilitySubsetFeaturesKHR queryPortabilityFeatures(VkPhysicalDevice pd, MemoryStack stack) {
        VkPhysicalDevicePortabilitySubsetFeaturesKHR portability = VkPhysicalDevicePortabilitySubsetFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(portability.address());
        VK11.vkGetPhysicalDeviceFeatures2(pd, features2);
        return portability;
    }

    static String vendorName(int vendorId) {
        return switch (vendorId) {
            case 0x10DE -> "NVIDIA Corporation";
            case 0x1002 -> "ATI Technologies Inc.";
            case 0x8086 -> "Intel";
            case 0x106B -> "Apple";
            case 0x13B5 -> "ARM";
            case 0x5143 -> "Qualcomm";
            case 0x1010 -> "Imagination Technologies";
            default -> String.format(Locale.ROOT, "0x%04X", vendorId);
        };
    }

    static String versionString(int version) {
        return VK_API_VERSION_MAJOR(version) + "." + VK_API_VERSION_MINOR(version) + "." + VK_API_VERSION_PATCH(version);
    }

    // ---------------------------------------------------------------------------------------------------------
    // logical device
    // ---------------------------------------------------------------------------------------------------------

    private static void createDevice(Context c) {
        List<String> extensions = new ArrayList<>();
        extensions.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        if (c.deviceExtensions.contains(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)) {
            extensions.add(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME); // mandatory to enable when exposed (MoltenVK)
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(c.queueFamily)
                    .pQueuePriorities(stack.floats(1.0f));

            // calloc: every feature we do not name must be VK_FALSE (a malloc'd struct enables random features)
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack)
                    .wideLines(c.info.wideLines())
                    .fillModeNonSolid(c.info.fillModeNonSolid())
                    .samplerAnisotropy(c.info.samplerAnisotropy())
                    .logicOp(c.info.logicOp());

            PointerBuffer ppExtensions = stack.mallocPointer(extensions.size());
            for (String extension : extensions) {
                ppExtensions.put(stack.UTF8(extension));
            }
            ppExtensions.flip();

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueInfo)
                    .ppEnabledExtensionNames(ppExtensions)
                    .pEnabledFeatures(features);
            if (extensions.contains(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)) {
                // enables every optional portability feature the device offers (swizzled image views, sampler LOD bias, ...)
                createInfo.pNext(queryPortabilityFeatures(c.physicalDevice, stack).address());
            }

            PointerBuffer pDevice = stack.mallocPointer(1);
            VkUtil.check(vkCreateDevice(c.physicalDevice, createInfo, null, pDevice), "vkCreateDevice");
            c.device = new VkDevice(pDevice.get(0), c.physicalDevice, createInfo);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(c.device, c.queueFamily, 0, pQueue);
            c.queue = new VkQueue(pQueue.get(0), c.device);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // VMA
    // ---------------------------------------------------------------------------------------------------------

    private static void createAllocator(Context c) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(c.instance, c.device);
            // VMA must not be told a higher version than the instance was created with
            int vmaVersion = Math.min(c.instanceApiVersion & ~0xFFF, c.deviceApiVersion & ~0xFFF);
            VmaAllocatorCreateInfo info = VmaAllocatorCreateInfo.calloc(stack)
                    .physicalDevice(c.physicalDevice)
                    .device(c.device)
                    .instance(c.instance)
                    .pVulkanFunctions(functions)
                    .vulkanApiVersion(vmaVersion);
            PointerBuffer pAllocator = stack.mallocPointer(1);
            VkUtil.check(Vma.vmaCreateAllocator(info, pAllocator), "vmaCreateAllocator");
            c.allocator = pAllocator.get(0);
        }
    }
}
