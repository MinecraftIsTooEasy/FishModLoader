package net.xiaoyu233.fml.vulkan.mixin.opt.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import net.minecraft.AxisAlignedBB;
import net.minecraft.Chunk;
import net.minecraft.Entity;
import net.minecraft.IEntitySelector;
import net.minecraft.MathHelper;
import net.minecraft.World;
import net.xiaoyu233.fml.vulkan.opt.entity.EntityIndexStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Times the world level queries (same loop as vanilla) and replaces the O(n*m) loadedEntityList.removeAll with a hash lookup. */
@Mixin(World.class)
public abstract class WorldEntityQueryMixin {
    @Shadow public List loadedEntityList;
    @Shadow protected abstract boolean chunkExists(int x, int z);
    @Shadow public abstract Chunk getChunkFromChunkCoords(int x, int z);

    @Inject(method = "getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/Entity;Lnet/minecraft/AxisAlignedBB;Lnet/minecraft/IEntitySelector;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true)
    private void fml$excluding(Entity e, AxisAlignedBB bb, IEntitySelector sel, CallbackInfoReturnable<List> cir) {
        long t0 = System.nanoTime();
        ArrayList out = new ArrayList();
        int x0 = MathHelper.floor_double((bb.minX - 2.0) / 16.0);
        int x1 = MathHelper.floor_double((bb.maxX + 2.0) / 16.0);
        int z0 = MathHelper.floor_double((bb.minZ - 2.0) / 16.0);
        int z1 = MathHelper.floor_double((bb.maxZ + 2.0) / 16.0);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (this.chunkExists(x, z)) {
                    this.getChunkFromChunkCoords(x, z).getEntitiesWithinAABBForEntity(e, bb, out, sel);
                }
            }
        }
        EntityIndexStats.record(t0);
        cir.setReturnValue(out);
    }

    @Inject(method = "selectEntitiesWithinAABB(Ljava/lang/Class;Lnet/minecraft/AxisAlignedBB;Lnet/minecraft/IEntitySelector;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true)
    private void fml$select(Class cls, AxisAlignedBB bb, IEntitySelector sel, CallbackInfoReturnable<List> cir) {
        long t0 = System.nanoTime();
        ArrayList out = new ArrayList();
        int x0 = MathHelper.floor_double((bb.minX - 2.0) / 16.0);
        int x1 = MathHelper.floor_double((bb.maxX + 2.0) / 16.0);
        int z0 = MathHelper.floor_double((bb.minZ - 2.0) / 16.0);
        int z1 = MathHelper.floor_double((bb.maxZ + 2.0) / 16.0);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (this.chunkExists(x, z)) {
                    this.getChunkFromChunkCoords(x, z).getEntitiesOfTypeWithinAAAB(cls, bb, out, sel);
                }
            }
        }
        EntityIndexStats.record(t0);
        cir.setReturnValue(out);
    }

    /**
     * loadedEntityList.removeAll(unloadedEntityList): ArrayList.removeAll is O(n*m) with List.contains. Entity.equals is by entityId, so a
     * HashSet of the unloaded entities gives the same removal set; the survivors keep their order.
     */
    @Redirect(method = "updateEntities", at = @At(value = "INVOKE", target = "Ljava/util/List;removeAll(Ljava/util/Collection;)Z"))
    private boolean fml$removeAll(List list, Collection c) {
        if (!EntityIndexStats.INDEX || list != this.loadedEntityList || c.size() < 8 || list.size() < 64) {
            return list.removeAll(c);
        }
        HashSet<Object> set = new HashSet<>(c);
        int n = list.size();
        int w = 0;
        for (int r = 0; r < n; r++) {
            Object o = list.get(r);
            if (!set.contains(o)) {
                if (w != r) {
                    list.set(w, o);
                }
                w++;
            }
        }
        if (w == n) {
            return false;
        }
        list.subList(w, n).clear();
        return true;
    }
}
