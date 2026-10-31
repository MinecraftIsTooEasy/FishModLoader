package net.xiaoyu233.fml.vulkan.render.texture;

/** Opacity and size information of a registered atlas (1.11). */
public interface AtlasInfo {
    boolean isOpaque(float u, float v);

    int width();

    int height();

    int opacityVersion();
}
