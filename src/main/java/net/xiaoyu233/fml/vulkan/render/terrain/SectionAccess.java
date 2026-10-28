package net.xiaoyu233.fml.vulkan.render.terrain;

/** Mixed into {@code WorldRenderer} by NR-09: access to the native section state. */
public interface SectionAccess {
    Section fml$section();
}
