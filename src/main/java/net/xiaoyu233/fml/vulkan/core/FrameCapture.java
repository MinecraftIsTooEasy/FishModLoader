package net.xiaoyu233.fml.vulkan.core;

import net.xiaoyu233.fml.vulkan.VkLog;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.vulkan.VK10.*;

/**
 * {@code fml.vulkan.capture}: writes the offscreen colour image of the requested presented frames as PNG. The copy into a host-cached
 * buffer is recorded into the frame's main command buffer; the pixels are collected once the frame slot's fence has passed (no stall)
 * and encoded by a background thread (never the render thread: AWT must stay off it on macOS).
 */
final class FrameCapture {
    private final int[] frames;
    private final Path directory;
    private final boolean swapRedBlue;
    private final ArrayList<Pending> pending = new ArrayList<>();
    private final java.util.ArrayList<Request> requested = new java.util.ArrayList<>();
    private String dueName;
    private int next;
    private ExecutorService writer;

    private static final class Pending {
        final int slot;
        final long frame;
        final int width;
        final int height;
        final long buffer;
        final long allocation;
        final long address;
        final boolean coherent;
        final String name;

        Pending(int slot, long frame, int width, int height, long buffer, long allocation, long address, boolean coherent, String name) {
            this.slot = slot;
            this.frame = frame;
            this.width = width;
            this.height = height;
            this.buffer = buffer;
            this.allocation = allocation;
            this.address = address;
            this.coherent = coherent;
            this.name = name;
        }
    }

    /** @param bgra the colour image is B8G8R8A8 (red and blue are swapped in memory) */
    FrameCapture(int[] frames, Path directory, boolean bgra) {
        this.frames = frames;
        this.directory = directory;
        this.swapRedBlue = bgra;
    }

    boolean enabled() {
        return frames.length > 0;
    }

    /**
     * Test harness: capture a presented frame into {@code <captureDir>/<name>.png} (any thread). {@code delay} = 0 captures the next
     * presented frame, 1 the one after it.
     */
    void request(String name, int delay) {
        synchronized (requested) {
            requested.add(new Request(name, delay));
        }
    }

    private static final class Request {
        final String name;
        int delay;

        Request(String name, int delay) {
            this.name = name;
            this.delay = delay;
        }
    }

    /**
     * True once for every frame number listed in the configuration (frame numbers must be queried in increasing order) and for every
     * frame a {@link #request} selected.
     */
    boolean isDue(long frameNumber) {
        dueName = null;
        synchronized (requested) {
            for (java.util.Iterator<Request> it = requested.iterator(); it.hasNext(); ) {
                Request r = it.next();
                if (r.delay <= 0) {
                    if (dueName == null) {
                        dueName = r.name;
                    }
                    it.remove();
                } else {
                    r.delay--;
                }
            }
        }
        while (next < frames.length && frames[next] < frameNumber) {
            next++;
        }
        return dueName != null || (next < frames.length && frames[next] == frameNumber);
    }

    /** Records the colour -> host buffer copy; {@code colorImage} must be in TRANSFER_SRC_OPTIMAL. */
    void record(VkCommandBuffer cb, long colorImage, int width, int height, int slot, long frameNumber) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long size = (long) width * height * 4;
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            VkUtil.check(Vma.vmaCreateBuffer(RenderCore.allocator(), bufferInfo, allocationInfo, pBuffer, pAllocation, info), "vmaCreateBuffer (capture)");
            long buffer = pBuffer.get(0);
            long allocation = pAllocation.get(0);
            IntBuffer pFlags = stack.mallocInt(1);
            Vma.vmaGetAllocationMemoryProperties(RenderCore.allocator(), allocation, pFlags);
            boolean coherent = (pFlags.get(0) & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(cb, colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);

            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer)
                    .offset(0)
                    .size(VK_WHOLE_SIZE);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);

            pending.add(new Pending(slot, frameNumber, width, height, buffer, allocation, info.pMappedData(), coherent, dueName));
        }
    }

    /** The fence of {@code slot} has passed: its captures are complete. */
    void collect(int slot) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            if (pending.get(i).slot == slot) {
                process(pending.remove(i));
            }
        }
    }

    /** The device is idle: finish every outstanding capture. */
    void collectAll() {
        while (!pending.isEmpty()) {
            process(pending.remove(pending.size() - 1));
        }
    }

    /** Waits (bounded) for the PNG writer to finish. */
    void shutdown() {
        collectAll();
        if (writer != null) {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                    VkLog.LOGGER.warn("Frame capture writer did not finish within 30 s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writer = null;
        }
    }

    private void process(Pending p) {
        int[] rgb = new int[p.width * p.height];
        try {
            if (!p.coherent) {
                Vma.vmaInvalidateAllocation(RenderCore.allocator(), p.allocation, 0, VK_WHOLE_SIZE);
            }
            ByteBuffer src = MemoryUtil.memByteBuffer(p.address, rgb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < rgb.length; i++) {
                int v = src.getInt(i << 2); // memory order R,G,B,A (or B,G,R,A) read as little endian
                rgb[i] = swapRedBlue ? (v & 0xFFFFFF) : (((v & 0xFF) << 16) | (v & 0xFF00) | ((v >>> 16) & 0xFF));
            }
        } finally {
            Vma.vmaDestroyBuffer(RenderCore.allocator(), p.buffer, p.allocation);
        }
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "FML-Vulkan-Capture");
                thread.setDaemon(true);
                return thread;
            });
        }
        final int width = p.width;
        final int height = p.height;
        final long frame = p.frame;
        final String name = p.name;
        writer.execute(() -> {
            try {
                // TYPE_INT_RGB drops the alpha channel (the game clears with alpha 0, a PNG with alpha would be fully transparent)
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, width, height, rgb, 0, width);
                Files.createDirectories(directory);
                Path file = directory.resolve((name != null ? name : "frame-" + frame) + ".png");
                ImageIO.write(image, "png", file.toFile());
                VkLog.LOGGER.info("Captured frame {} ({}x{}) -> {}", frame, width, height, file);
            } catch (Throwable t) {
                VkLog.LOGGER.error("Failed to write the capture of frame {}", frame, t);
            }
        });
    }
}
