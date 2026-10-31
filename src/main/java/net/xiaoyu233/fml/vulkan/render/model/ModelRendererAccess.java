package net.xiaoyu233.fml.vulkan.render.model;

/** Duck interface implemented by ModelRendererEntityMixin: the baked part cached on a ModelRenderer. */
public interface ModelRendererAccess {
    BakedPart fml$baked();

    void fml$setBaked(BakedPart part);
}
