package net.xiaoyu233.fml.vulkan.mixin.nat.terrain;

import net.minecraft.RenderList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "terrain": RenderList (display-list batching) is inert; terrain is drawn by TerrainRenderer. */
@Mixin(RenderList.class)
public abstract class RenderListTerrainMixin {
    /**
     * @author fml-native
     * @reason native renderer: no display lists to batch
     */
    @Overwrite
    public void func_78422_a(int x, int y, int z, double cx, double cy, double cz) {
    }

    /**
     * @author fml-native
     * @reason native renderer: never matches, nothing is batched
     */
    @Overwrite
    public boolean func_78418_a(int x, int y, int z) {
        return false;
    }

    /**
     * @author fml-native
     * @reason native renderer: no display list ids are collected
     */
    @Overwrite
    public void func_78420_a(int listId) {
    }

    /**
     * @author fml-native
     * @reason native renderer: no glCallLists
     */
    @Overwrite
    public void func_78419_a() {
    }

    /**
     * @author fml-native
     * @reason native renderer: nothing to reset
     */
    @Overwrite
    public void func_78421_b() {
    }
}
