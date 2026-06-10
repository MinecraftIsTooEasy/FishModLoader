package net.xiaoyu233.fml.reload.transform.fix.skin;

import net.minecraft.client.renderer.IImageBuffer;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ThreadDownloadImageData.class)
public interface ThreadDownloadImageDataAccessor {
    @Accessor(value = "imageUrl")
    String getImageUrl();

    @Accessor(value = "imageBuffer")
    IImageBuffer getImageBuffer();
}
