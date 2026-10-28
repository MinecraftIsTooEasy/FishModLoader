package net.xiaoyu233.fml.vulkan.render.state;

import net.xiaoyu233.fml.vulkan.render.buffer.StaticMesh;

/**
 * Receiver of {@link Draw#drawStatic} (1.6): the batching layer (NR-03) registers one with {@link Draw#setStaticDrawHandler} and enqueues
 * an ordered entry. {@code model} is the live top of the pose stack (copy it), {@code colorAbgr} the current colour as colour modulator.
 */
public interface StaticDrawHandler {
    void drawStatic(StaticMesh mesh, RenderType type, float[] model, int colorAbgr);
}
