package net.xiaoyu233.fml.vulkan.core;

import java.nio.ByteBuffer;

/**
 * A slice of one of the per-frame host-visible stream buffers (see {@code RenderCore.streamVertex/streamIndex/streamUniform/streamStaging}).
 *
 * @param buffer  VkBuffer handle
 * @param offset  byte offset of the slice inside {@code buffer}
 * @param address native memory address of the first byte of {@code mapped} (for unsafe/MemoryUtil style writes)
 * @param mapped  persistently mapped memory of the slice: position 0, limit = requested bytes
 */
public record StreamSlice(long buffer, long offset, long address, ByteBuffer mapped) {
}
