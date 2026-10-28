package net.xiaoyu233.fml.vulkan.pipeline;

import net.xiaoyu233.fml.vulkan.render.gpu.CommandStream;

/**
 * The phase-1 recorder API (signatures are the binding contract of ../docs/ARCHITECTURE.md, outside the repo, section 5.7) as a thin
 * facade over {@link CommandStream}: the GL 1.5 emulation and the native renderer share one set of bind caches and one dynamic state
 * tracker, so a native batch after an emulated draw (and the other way round) never re-binds what is already bound. All logic lives in
 * {@code CommandStream}; every method here forwards one to one. Render thread only.
 */
public final class CommandRecorder {
    private CommandRecorder() {
    }

    /** Draw statistics of one frame, see {@link CommandStream.Stats}. */
    public record Stats(int drawCalls, int indexedDrawCalls, int pipelineBinds, int descriptorBinds, int pushConstantUpdates,
                        int vertexBufferBinds, int redundantBindsSkipped) {
        static Stats of(CommandStream.Stats s) {
            return new Stats(s.drawCalls(), s.indexedDrawCalls(), s.pipelineBinds(), s.descriptorBinds(), s.pushConstantUpdates(),
                    s.vertexBufferBinds(), s.redundantBindsSkipped());
        }
    }

    // lifecycle (called by Pipelines)

    static void onPipelinesInit() {
        CommandStream.onPipelinesInit();
    }

    static void onPipelinesShutdown() {
        CommandStream.onPipelinesShutdown();
    }

    /** Forgets everything the recorder believes the command buffer has bound and marks all dynamic state dirty. */
    public static void reset() {
        CommandStream.invalidate();
    }

    // binds

    /** 0 binds nothing and makes the following draws drop until a real pipeline is bound. */
    public static void bindPipeline(long pipeline) {
        CommandStream.bindPipeline(pipeline);
    }

    public static void bindUniforms(long uniformSet, int dynamicOffset) {
        CommandStream.bindUniforms(uniformSet, dynamicOffset);
    }

    /** Unit 0 binds set 1, unit 1 binds set 2. */
    public static void bindTexture(int unit, long descriptorSet) {
        CommandStream.bindTexture(unit, descriptorSet);
    }

    /** {@code address} = native memory holding the 128 push-constant bytes (8-byte aligned). */
    public static void pushConstants(long address) {
        CommandStream.pushConstants(address);
    }

    public static void bindVertexBuffer(int binding, long buffer, long offset) {
        CommandStream.bindVertexBuffer(binding, buffer, offset);
    }

    public static void bindIndexBuffer(long buffer, long offset, int vkIndexType) {
        CommandStream.bindIndexBuffer(buffer, offset, vkIndexType);
    }

    // dynamic state

    /** Vulkan coords; negative height allowed; depth 0..1. */
    public static void setViewport(float x, float y, float w, float h) {
        CommandStream.setViewport(x, y, w, h);
    }

    /** Vulkan coords (top-left origin); the box may exceed the framebuffer, it is clamped when it is recorded. */
    public static void setScissor(int x, int y, int w, int h) {
        CommandStream.setScissor(x, y, w, h);
    }

    public static void setDepthBias(float constantFactor, float slopeFactor) {
        CommandStream.setDepthBias(constantFactor, slopeFactor);
    }

    public static void setLineWidth(float width) {
        CommandStream.setLineWidth(width);
    }

    public static void setBlendConstants(float r, float g, float b, float a) {
        CommandStream.setBlendConstants(r, g, b, a);
    }

    // draws

    public static void draw(int vertexCount, int firstVertex) {
        CommandStream.draw(vertexCount, firstVertex);
    }

    public static void drawIndexed(int indexCount, int firstIndex, int vertexOffset) {
        CommandStream.drawIndexed(indexCount, firstIndex, vertexOffset);
    }

    // debug counters

    public static int drawCalls() {
        return CommandStream.drawCalls();
    }

    public static int indexedDrawCalls() {
        return CommandStream.indexedDrawCalls();
    }

    public static int pipelineBinds() {
        return CommandStream.pipelineBinds();
    }

    public static int descriptorBinds() {
        return CommandStream.descriptorBinds();
    }

    public static int pushConstantUpdates() {
        return CommandStream.pushConstantUpdates();
    }

    public static int vertexBufferBinds() {
        return CommandStream.vertexBufferBinds();
    }

    public static int redundantBindsSkipped() {
        return CommandStream.redundantBindsSkipped();
    }

    /** The counters since the current frame began. */
    public static Stats currentStats() {
        return Stats.of(CommandStream.currentStats());
    }

    /** The counters of the previous frame. */
    public static Stats lastFrameStats() {
        return Stats.of(CommandStream.lastFrameStats());
    }

    public static void resetCounters() {
        CommandStream.resetCounters();
    }
}
