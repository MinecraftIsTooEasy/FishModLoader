package net.xiaoyu233.fml.vulkan.render.texture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import net.minecraft.AnimationMetadataSection;
import net.minecraft.Resource;
import net.minecraft.ResourceLocation;
import net.minecraft.ResourceManager;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshWorkers;

/**
 * Parallel sprite decode of an atlas reload (TEX-01, 1.11). Phase 1 runs on the render thread in the original sprite order: it asks the
 * resource manager for each sprite (resource packs are not touched from another thread), reads the PNG bytes and the animation
 * metadata and hands the bytes to the worker pool. Phase 2 (also the render thread, same order) collects the decoded pixels; the
 * sprite is loaded from the bytes that were read, so the decoded data is by construction the data of the resource of this reload.
 * Failures are kept per sprite and rethrown in phase 2, where the caller logs them exactly like the serial loader. When the pool
 * refuses a task, or there are too few sprites to pay off, the decode runs serially on the render thread; {@code fml.vulkan.atlasDecode}
 * ({@code serial} forces that mode).
 */
public final class AtlasPrefetch {
    private static final int PARALLEL_MIN_SPRITES = 8;
    /** A decode that a busy shared pool has not finished by then is redone on the render thread (no unbounded wait on the pool). */
    private static final long DECODE_TIMEOUT_SECONDS = 20L;

    /** A decoded image: ARGB ints, row 0 first (what {@code BufferedImage.getRGB} returns). */
    private record Decoded(int width, int height, int[] pixels) {
    }

    private static final class Slot {
        Throwable error;
        AnimationMetadataSection animation;
        byte[] bytes;
        CompletableFuture<Decoded> future;
    }

    private final Slot[] slots;
    private final Executor executor;
    private final boolean parallel;
    private int parallelCount;
    private long decodeNanos;

    /** @param count number of sprites that will be fetched */
    public AtlasPrefetch(int count) {
        this.slots = new Slot[count];
        String mode = System.getProperty("fml.vulkan.atlasDecode", "parallel");
        Executor pool = MeshWorkers.pool();
        this.executor = pool;
        this.parallel = pool != null && count >= PARALLEL_MIN_SPRITES && !"serial".equalsIgnoreCase(mode.trim());
    }

    /**
     * Phase 1 for sprite {@code index}: resource lookup, metadata and bytes on the calling (render) thread, decode on the pool.
     * Never throws: a failure is stored for {@link #load}.
     */
    public void fetch(int index, ResourceManager resources, ResourceLocation location) {
        Slot slot = new Slot();
        slots[index] = slot;
        try {
            Resource resource = resources.getResource(location);
            slot.animation = (AnimationMetadataSection) resource.getMetadata("animation");
            java.io.InputStream in = resource.getInputStream();
            try {
                slot.bytes = in.readAllBytes();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            slot.error = t;
            slot.bytes = null;
            return;
        }
        if (parallel) {
            byte[] bytes = slot.bytes;
            try {
                slot.future = CompletableFuture.supplyAsync(() -> decode(bytes), executor);
                parallelCount++;
            } catch (RejectedExecutionException rejected) {
                slot.future = null; // decoded serially in load()
            }
        }
    }

    /** Phase 2 for sprite {@code index}: loads {@code sprite} from the decoded pixels; throws what the serial {@code loadSprite} throws. */
    public void load(int index, DecodedSpriteTarget sprite) {
        Slot slot = slots[index];
        sprite.fml$resetSprite();
        if (slot.error != null) {
            Throwable t = slot.error;
            slots[index] = null;
            AtlasPrefetch.<RuntimeException>sneakyThrow(t);
            return;
        }
        Decoded decoded;
        long start = System.nanoTime();
        try {
            decoded = slot.future != null ? slot.future.get(DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS) : decode(slot.bytes);
        } catch (ExecutionException e) {
            slots[index] = null;
            Throwable cause = e.getCause() == null ? e : e.getCause();
            AtlasPrefetch.<RuntimeException>sneakyThrow(cause);
            return;
        } catch (TimeoutException e) {
            slot.future.cancel(false);
            VkLog.LOGGER.warn("Sprite decode {} did not finish in {} s on the worker pool: decoding it serially", index, DECODE_TIMEOUT_SECONDS);
            decoded = decode(slot.bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            decoded = decode(slot.bytes);
        }
        decodeNanos += System.nanoTime() - start;
        AnimationMetadataSection animation = slot.animation;
        slots[index] = null; // release the bytes
        if (decoded == null) {
            throw new NullPointerException("the image of sprite " + index + " could not be decoded");
        }
        sprite.fml$loadDecoded(decoded.width, decoded.height, decoded.pixels, animation);
    }

    /** Sprites that were decoded on the pool. */
    public int parallelCount() {
        return parallelCount;
    }

    /** Time the render thread spent waiting for / doing decodes in phase 2. */
    public long decodeWaitNanos() {
        return decodeNanos;
    }

    private static Decoded decode(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            int[] pixels = new int[w * h];
            image.getRGB(0, 0, w, h, pixels, 0, w);
            return new Decoded(w, h, pixels);
        } catch (IOException e) {
            VkLog.LOGGER.debug("Sprite decode failed", e);
            AtlasPrefetch.<RuntimeException>sneakyThrow(e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
