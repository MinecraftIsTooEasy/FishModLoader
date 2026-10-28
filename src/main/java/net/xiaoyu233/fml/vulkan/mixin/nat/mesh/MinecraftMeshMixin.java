package net.xiaoyu233.fml.vulkan.mixin.nat.mesh;

import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.render.mesh.ThreadSlots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "mesh": thread slots (2.3 #2). Owns {@code Minecraft.getThreadIndex()}. */
@Mixin(Minecraft.class)
public abstract class MinecraftMeshMixin {
    /**
     * @author NR-10
     * @reason 0 server thread, 1 render and every other non-worker thread, 2 + k for mesh worker k
     */
    @Overwrite
    public static int getThreadIndex() {
        return Thread.currentThread() == Minecraft.server_thread ? 0 : ThreadSlots.index();
    }
}
