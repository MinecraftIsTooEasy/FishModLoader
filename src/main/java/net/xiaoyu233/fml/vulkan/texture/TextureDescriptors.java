package net.xiaoyu233.fml.vulkan.texture;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.pipeline.Pipelines;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Growable descriptor pools for the texture descriptor sets (one COMBINED_IMAGE_SAMPLER at binding 0, layout from
 * {@code Pipelines.textureSetLayout()}). Every pool is created with FREE_DESCRIPTOR_SET so sets can be returned individually;
 * when all pools are exhausted (or fragmented) a new, larger one is added. Render thread only.
 */
final class TextureDescriptors {
    private static final int INITIAL_POOL_SETS = 256;
    private static final int MAX_POOL_SETS = 4096;

    private static final List<Pool> POOLS = new ArrayList<>();
    private static int nextCapacity = INITIAL_POOL_SETS;

    /** One VkDescriptorPool. {@code destroyed} guards deferred frees that run after {@link #shutdown()}. */
    static final class Pool {
        final long handle;
        final int capacity;
        int used;
        boolean destroyed;
        /** An allocation failed (exhausted / fragmented): skip this pool until a set is freed. */
        boolean blocked;

        Pool(long handle, int capacity) {
            this.handle = handle;
            this.capacity = capacity;
        }
    }

    record Allocation(long set, Pool pool) {
    }

    private TextureDescriptors() {
    }

    static void init() {
        POOLS.clear();
        nextCapacity = INITIAL_POOL_SETS;
    }

    /** Destroys every pool (and with it every set). The device must be idle. */
    static void shutdown() {
        for (Pool p : POOLS) {
            if (!p.destroyed) {
                p.destroyed = true;
                VK10.vkDestroyDescriptorPool(RenderCore.device(), p.handle, null);
            }
        }
        POOLS.clear();
        nextCapacity = INITIAL_POOL_SETS;
    }

    /** Allocates one texture descriptor set; null (after logging) if even a fresh pool cannot provide one. */
    static Allocation allocate() {
        for (int i = POOLS.size() - 1; i >= 0; i--) {
            Pool p = POOLS.get(i);
            if (p.blocked || p.used >= p.capacity) {
                continue;
            }
            long set = tryAllocate(p);
            if (set != 0L) {
                return new Allocation(set, p);
            }
        }
        Pool p = createPool();
        if (p == null) {
            return null;
        }
        long set = tryAllocate(p);
        if (set == 0L) {
            VkLog.LOGGER.error("Texture descriptor set allocation failed even in a new descriptor pool");
            return null;
        }
        return new Allocation(set, p);
    }

    private static long tryAllocate(Pool p) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(p.handle)
                .pSetLayouts(stack.longs(Pipelines.textureSetLayout()));
            LongBuffer pSet = stack.mallocLong(1);
            int result = VK10.vkAllocateDescriptorSets(RenderCore.device(), info, pSet);
            if (result == VK10.VK_SUCCESS) {
                p.used++;
                return pSet.get(0);
            }
            // VK_ERROR_OUT_OF_POOL_MEMORY / VK_ERROR_FRAGMENTED_POOL: try the next pool; anything else is a real error
            p.blocked = true;
            if (result != VK10.VK_ERROR_FRAGMENTED_POOL && result != VK11.VK_ERROR_OUT_OF_POOL_MEMORY) {
                VkLog.LOGGER.error("vkAllocateDescriptorSets failed: VkResult {}", result);
            }
            return 0L;
        }
    }

    private static Pool createPool() {
        int capacity = nextCapacity;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(capacity);
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .flags(VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                .maxSets(capacity)
                .pPoolSizes(sizes);
            LongBuffer pPool = stack.mallocLong(1);
            int result = VK10.vkCreateDescriptorPool(RenderCore.device(), info, null, pPool);
            if (result != VK10.VK_SUCCESS) {
                VkLog.LOGGER.error("vkCreateDescriptorPool failed: VkResult {}", result);
                return null;
            }
            Pool pool = new Pool(pPool.get(0), capacity);
            POOLS.add(pool);
            nextCapacity = Math.min(MAX_POOL_SETS, capacity * 2);
            if (POOLS.size() > 1) {
                VkLog.LOGGER.debug("Texture descriptor pool #{} created ({} sets)", POOLS.size(), capacity);
            }
            return pool;
        }
    }

    /** Points the binding of {@code set} at (sampler, imageView in SHADER_READ_ONLY_OPTIMAL). */
    static void write(long set, long sampler, long imageView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(sampler)
                .imageView(imageView)
                .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set)
                .dstBinding(0)
                .dstArrayElement(0)
                .descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(imageInfo);
            VK10.vkUpdateDescriptorSets(RenderCore.device(), write, null);
        }
    }

    /** Frees the set once every frame that could still reference it has completed. */
    static void deferFree(long set, Pool pool) {
        if (set == 0L || pool == null) {
            return;
        }
        RenderCore.deferDestroy(() -> free(set, pool));
    }

    /** Immediate free (only valid when no pending command buffer uses the set). No-op after the pool was destroyed. */
    static void free(long set, Pool pool) {
        if (pool.destroyed) {
            return;
        }
        VK10.vkFreeDescriptorSets(RenderCore.device(), pool.handle, set);
        pool.used--;
        pool.blocked = false;
    }
}
