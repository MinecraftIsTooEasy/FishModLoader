package net.xiaoyu233.fml.vulkan.texture;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.gl.GLUnsupported;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Sampler cache. Samplers are keyed by the final Vulkan values (filters, mipmap mode, address modes, border colour, anisotropy,
 * LOD bias, min/max LOD), never by the GL enums, so two GL states that map to the same sampler share it and two states that do not
 * never collide. Samplers belong to the cache only: textures never destroy them, {@link #shutdown()} destroys each exactly once.
 */
final class TextureSamplers {
    /** Final Vulkan sampler parameters (floats as bit patterns so the record is a precise map key). */
    record Key(int minFilter, int magFilter, int mipmapMode, int addressU, int addressV, int borderColor, boolean anisotropy,
               int maxAnisotropyBits, int lodBiasBits, int minLodBits, int maxLodBits) {
        float maxAnisotropy() {
            return Float.intBitsToFloat(maxAnisotropyBits);
        }

        float lodBias() {
            return Float.intBitsToFloat(lodBiasBits);
        }

        float minLod() {
            return Float.intBitsToFloat(minLodBits);
        }

        float maxLod() {
            return Float.intBitsToFloat(maxLodBits);
        }
    }

    private static final Map<Key, Long> CACHE = new HashMap<>();
    private static long fallback;
    private static float maxSamplerLodBias = 2f;
    private static boolean lodBiasSupported = true;
    private static boolean swizzleSupported = true;

    private TextureSamplers() {
    }

    static void init() {
        CACHE.clear();
        maxSamplerLodBias = 2f;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(RenderCore.physicalDevice(), props);
            maxSamplerLodBias = props.limits().maxSamplerLodBias();
        }
        // VK_KHR_portability_subset (MoltenVK): a sampler LOD bias (samplerMipLodBias) and a swizzled view (imageViewFormatSwizzle) are
        // optional features. RenderCore enables the ones the physical device offers (DeviceInfo says which; every device without the
        // extension has both): using one that is not enabled breaks VUID-VkSamplerCreateInfo-samplerMipLodBias-04467 and
        // VUID-VkImageViewCreateInfo-imageViewFormatSwizzle-04465.
        DeviceInfo deviceInfo = RenderCore.deviceInfo();
        lodBiasSupported = deviceInfo.portabilitySamplerMipLodBias();
        swizzleSupported = deviceInfo.portabilityImageViewFormatSwizzle();
        if (!lodBiasSupported || !swizzleSupported) {
            VkLog.LOGGER.debug("Portability subset device without samplerMipLodBias={} / imageViewFormatSwizzle={}: "
                    + "texture LOD bias / alpha-opaque view swizzles are not used", !lodBiasSupported, !swizzleSupported);
        }
        fallback = get(new TextureObject.Params(), 1);
        if (fallback == 0L) {
            throw new IllegalStateException("Failed to create the fallback texture sampler");
        }
    }

    /** Destroys every cached sampler (once). The device must be idle. */
    static void shutdown() {
        for (long sampler : CACHE.values()) {
            if (sampler != 0L) {
                VK10.vkDestroySampler(RenderCore.device(), sampler, null);
            }
        }
        CACHE.clear();
        fallback = 0L;
    }

    /** Can an image view remap alpha to ONE (VkImageViewCreateInfo.components) on this device? */
    static boolean viewSwizzleSupported() {
        return swizzleSupported;
    }

    /** The shared sampler for the texture parameters {@code p} of a view with {@code viewLevels} levels. Never 0 after init. */
    static long get(TextureObject.Params p, int viewLevels) {
        DeviceInfo info = RenderCore.deviceInfo();
        if (!lodBiasSupported && p.lodBias != 0f) {
            GLUnsupported.call("GL_TEXTURE_LOD_BIAS (the device does not enable samplerMipLodBias)"); // logged once
        }
        Key key = keyFor(p, viewLevels, info.samplerAnisotropy(), info.maxAnisotropy(), maxSamplerLodBias, lodBiasSupported);
        Long cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        long sampler = create(key);
        if (sampler == 0L) {
            return fallback;
        }
        CACHE.put(key, sampler);
        return sampler;
    }

    /** Maps GL texture parameters to the final Vulkan sampler values. Pure function (unit-testable). */
    static Key keyFor(TextureObject.Params p, int viewLevels, boolean anisotropySupported, float deviceMaxAnisotropy,
                      float deviceMaxLodBias, boolean lodBiasSupported) {
        int min = p.minFilter;
        boolean mipmapped = min == GL11.GL_NEAREST_MIPMAP_NEAREST || min == GL11.GL_LINEAR_MIPMAP_NEAREST
            || min == GL11.GL_NEAREST_MIPMAP_LINEAR || min == GL11.GL_LINEAR_MIPMAP_LINEAR;
        boolean minNearest = min == GL11.GL_NEAREST || min == GL11.GL_NEAREST_MIPMAP_NEAREST || min == GL11.GL_NEAREST_MIPMAP_LINEAR;
        int vkMin = minNearest ? VK10.VK_FILTER_NEAREST : VK10.VK_FILTER_LINEAR;
        int vkMag = p.magFilter == GL11.GL_NEAREST ? VK10.VK_FILTER_NEAREST : VK10.VK_FILTER_LINEAR;
        int mipmapMode = VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
        // base level only: 0.25 keeps the min/mag filter distinction (lambda > 0 minifies) while never leaving the base level
        float minLod = 0f;
        float maxLod = 0.25f;
        if (mipmapped) {
            float userMax = Float.isNaN(p.maxLod) ? 1000f : p.maxLod;
            float userMin = Float.isNaN(p.minLod) ? -1000f : p.minLod;
            float top = Math.max(0f, Math.min(userMax, Math.max(0, viewLevels - 1)));
            // Vulkan chooses between magnification and minification with the clamped LOD, so a range that ends at 0 (a single level view,
            // or GL_TEXTURE_MAX_LOD <= 0) would always magnify. GL decides before the clamp and minifies with the min filter: such a
            // range keeps the base level only setup above.
            if (top > 0f) {
                maxLod = top;
                minLod = Math.max(0f, Math.min(userMin, top));
                if (min == GL11.GL_NEAREST_MIPMAP_LINEAR || min == GL11.GL_LINEAR_MIPMAP_LINEAR) {
                    mipmapMode = VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR;
                }
            }
        }
        float userBias = Float.isNaN(p.lodBias) ? 0f : p.lodBias;
        float bias = lodBiasSupported ? Math.max(-deviceMaxLodBias, Math.min(deviceMaxLodBias, userBias)) : 0f;
        boolean aniso = anisotropySupported && p.maxAnisotropy > 1f;
        float maxAniso = aniso ? Math.min(p.maxAnisotropy, Math.max(1f, deviceMaxAnisotropy)) : 1f;
        return new Key(vkMin, vkMag, mipmapMode, wrapToVk(p.wrapS), wrapToVk(p.wrapT), borderToVk(p.borderColor), aniso,
            bits(maxAniso), bits(bias), bits(minLod), bits(maxLod));
    }

    private static int bits(float f) {
        return f == 0f ? 0 : Float.floatToIntBits(f); // -0.0 and 0.0 are the same sampler
    }

    private static int wrapToVk(int glWrap) {
        return switch (glWrap) {
            case GL11.GL_CLAMP, GL12.GL_CLAMP_TO_EDGE -> VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case GL13.GL_CLAMP_TO_BORDER -> VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
            case GL14.GL_MIRRORED_REPEAT -> VK10.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            default -> VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;
        };
    }

    /** Only the three fixed Vulkan border colours exist: pick the closest one. */
    private static int borderToVk(float[] c) {
        boolean white = c[0] >= 0.5f && c[1] >= 0.5f && c[2] >= 0.5f;
        boolean opaque = c[3] >= 0.5f;
        if (!opaque) {
            return VK10.VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
        }
        return white ? VK10.VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE : VK10.VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
    }

    private static long create(Key k) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(k.magFilter())
                .minFilter(k.minFilter())
                .mipmapMode(k.mipmapMode())
                .addressModeU(k.addressU())
                .addressModeV(k.addressV())
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .mipLodBias(k.lodBias())
                .anisotropyEnable(k.anisotropy())
                .maxAnisotropy(k.maxAnisotropy())
                .compareEnable(false)
                .compareOp(VK10.VK_COMPARE_OP_ALWAYS)
                .minLod(k.minLod())
                .maxLod(k.maxLod())
                .borderColor(k.borderColor())
                .unnormalizedCoordinates(false);
            LongBuffer pSampler = stack.mallocLong(1);
            int result = VK10.vkCreateSampler(RenderCore.device(), info, null, pSampler);
            if (result != VK10.VK_SUCCESS) {
                VkLog.LOGGER.error("vkCreateSampler failed: VkResult {} ({})", result, k);
                return 0L;
            }
            return pSampler.get(0);
        }
    }
}
