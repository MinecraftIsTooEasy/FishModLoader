package net.xiaoyu233.fml.vulkan.render.state;

/** Frame phases (1.2). */
public enum Phase {
    SKY, CLOUDS, TERRAIN, ENTITIES, PARTICLES, TRANSLUCENT, OVERLAY, HAND, GUI;

    /** True for the phase whose geometry is batched per render type (ENTITIES); the others are ordered streams (1.8). */
    public boolean batched() {
        return this == ENTITIES;
    }
}
