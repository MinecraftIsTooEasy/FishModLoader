package net.xiaoyu233.fml.vulkan.mixin.nat.core;

import java.util.List;
import java.util.Map;
import net.minecraft.GLAllocation;
import net.xiaoyu233.fml.vulkan.render.buffer.StaticMeshes;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "core": display-list ids are {@code StaticMeshes} ids, texture deletion goes through {@code Textures}. */
@Mixin(GLAllocation.class)
public abstract class GLAllocationCoreMixin {
    @Shadow
    @Final
    private static Map field_74531_a;
    @Shadow
    @Final
    private static List field_74530_b;

    /**
     * @author fml-native
     * @reason native renderer: reserves {@code count} consecutive StaticMeshes ids instead of GL display lists
     */
    @Overwrite
    public static synchronized int generateDisplayLists(int count) {
        int id = StaticMeshes.reserveIds(count);
        field_74531_a.put(id, count);
        return id;
    }

    /**
     * @author fml-native
     * @reason native renderer: frees the StaticMeshes ids of the range that was reserved at {@code id}
     */
    @Overwrite
    public static synchronized void deleteDisplayLists(int id) {
        Integer count = (Integer) field_74531_a.remove(id);
        deleteRange(id, count == null ? 1 : count);
    }

    /**
     * @author fml-native
     * @reason native renderer: texture names are Textures ids
     */
    @Overwrite
    public static synchronized void func_98302_b() {
        for (int i = 0; i < field_74530_b.size(); i++) {
            Textures.delete((Integer) field_74530_b.get(i));
        }
        field_74530_b.clear();
    }

    /**
     * @author fml-native
     * @reason native renderer: StaticMeshes ids instead of GL display lists
     */
    @Overwrite
    public static synchronized void deleteTexturesAndDisplayLists() {
        for (Object entry : field_74531_a.entrySet()) {
            Map.Entry e = (Map.Entry) entry;
            deleteRange((Integer) e.getKey(), (Integer) e.getValue());
        }
        field_74531_a.clear();
        func_98302_b();
    }

    private static void deleteRange(int id, int count) {
        for (int i = 0; i < count; i++) {
            if (StaticMeshes.exists(id + i)) {
                StaticMeshes.delete(id + i);
            }
        }
    }
}
