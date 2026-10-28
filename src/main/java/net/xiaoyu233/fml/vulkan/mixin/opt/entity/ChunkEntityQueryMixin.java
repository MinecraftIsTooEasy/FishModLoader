package net.xiaoyu233.fml.vulkan.mixin.opt.entity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.AxisAlignedBB;
import net.minecraft.Chunk;
import net.minecraft.Entity;
import net.minecraft.IEntitySelector;
import net.minecraft.MathHelper;
import net.xiaoyu233.fml.vulkan.opt.entity.EntityIndexStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Order preserving chunk scan: same section range, same section/insertion order, same predicate order as vanilla, but empty sections are
 * skipped, the query box is hoisted into locals and the class/box tests are inlined. verify=true also runs the vanilla scan and compares.
 */
@Mixin(Chunk.class)
public abstract class ChunkEntityQueryMixin {
    @Shadow private List[] entityLists;

    @Inject(method = "getEntitiesWithinAABBForEntity", at = @At("HEAD"), cancellable = true)
    private void fml$forEntity(Entity self, AxisAlignedBB bb, List out, IEntitySelector sel, CallbackInfo ci) {
        if (!EntityIndexStats.INDEX) {
            return;
        }
        int mark = out.size();
        List[] lists = this.entityLists;
        int lo = MathHelper.floor_double((bb.minY - 2.0) / 16.0);
        int hi = MathHelper.floor_double((bb.maxY + 2.0) / 16.0);
        if (lo < 0) {
            lo = 0;
            hi = Math.max(lo, hi);
        }
        if (hi >= lists.length) {
            hi = lists.length - 1;
            lo = Math.min(lo, hi);
        }
        final double bminX = bb.minX, bmaxX = bb.maxX, bminY = bb.minY, bmaxY = bb.maxY, bminZ = bb.minZ, bmaxZ = bb.maxZ;
        for (int s = lo; s <= hi; s++) {
            List l = lists[s];
            int n = l.size();
            if (n == 0) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                Entity e = (Entity) l.get(i);
                if (e == self) {
                    continue;
                }
                AxisAlignedBB b = e.boundingBox;
                if (b.maxX > bminX && b.minX < bmaxX && b.maxY > bminY && b.minY < bmaxY && b.maxZ > bminZ && b.minZ < bmaxZ
                        && (sel == null || sel.isEntityApplicable(e))) {
                    out.add(e);
                    Entity[] parts = e.getParts();
                    if (parts != null) {
                        for (Entity p : parts) {
                            if (p != self && p.boundingBox.intersectsWith(bb) && (sel == null || sel.isEntityApplicable(p))) {
                                out.add(p);
                            }
                        }
                    }
                }
            }
            // a list may be mutated by a selector in theory; vanilla re-reads size() each iteration
            if (l.size() != n) {
                // fall back to vanilla for this chunk to stay exact
                while (out.size() > mark) {
                    out.remove(out.size() - 1);
                }
                return;
            }
        }
        if (EntityIndexStats.VERIFY) {
            ArrayList ref = new ArrayList();
            this.fml$vanillaFor(self, bb, ref, sel);
            boolean eq = ref.size() == out.size() - mark;
            for (int i = 0; eq && i < ref.size(); i++) {
                eq = ref.get(i) == out.get(mark + i);
            }
            EntityIndexStats.check(eq, "Chunk.getEntitiesWithinAABBForEntity", ref.size(), out.size() - mark);
        }
        ci.cancel();
    }

    @Inject(method = "getEntitiesOfTypeWithinAAAB", at = @At("HEAD"), cancellable = true)
    private void fml$ofType(Class cls, AxisAlignedBB bb, List out, IEntitySelector sel, CallbackInfo ci) {
        if (!EntityIndexStats.INDEX) {
            return;
        }
        int mark = out.size();
        List[] lists = this.entityLists;
        int lo = MathHelper.floor_double((bb.minY - 2.0) / 16.0);
        int hi = MathHelper.floor_double((bb.maxY + 2.0) / 16.0);
        if (lo < 0) {
            lo = 0;
        } else if (lo >= lists.length) {
            lo = lists.length - 1;
        }
        if (hi >= lists.length) {
            hi = lists.length - 1;
        } else if (hi < 0) {
            hi = 0;
        }
        final double bminX = bb.minX, bmaxX = bb.maxX, bminY = bb.minY, bmaxY = bb.maxY, bminZ = bb.minZ, bmaxZ = bb.maxZ;
        for (int s = lo; s <= hi; s++) {
            List l = lists[s];
            int n = l.size();
            if (n == 0) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                Entity e = (Entity) l.get(i);
                AxisAlignedBB b = e.boundingBox;
                if (cls.isAssignableFrom(e.getClass())
                        && b.maxX > bminX && b.minX < bmaxX && b.maxY > bminY && b.minY < bmaxY && b.maxZ > bminZ && b.minZ < bmaxZ
                        && (sel == null || sel.isEntityApplicable(e))) {
                    out.add(e);
                }
            }
            if (l.size() != n) {
                while (out.size() > mark) {
                    out.remove(out.size() - 1);
                }
                return;
            }
        }
        if (EntityIndexStats.VERIFY) {
            ArrayList ref = new ArrayList();
            this.fml$vanillaType(cls, bb, ref, sel);
            boolean eq = ref.size() == out.size() - mark;
            for (int i = 0; eq && i < ref.size(); i++) {
                eq = ref.get(i) == out.get(mark + i);
            }
            EntityIndexStats.check(eq, "Chunk.getEntitiesOfTypeWithinAAAB", ref.size(), out.size() - mark);
        }
        ci.cancel();
    }

    // verbatim copies of the vanilla loops (reference for verify mode)
    private void fml$vanillaFor(Entity self, AxisAlignedBB bb, List out, IEntitySelector sel) {
        int lo = MathHelper.floor_double((bb.minY - 2.0) / 16.0);
        int hi = MathHelper.floor_double((bb.maxY + 2.0) / 16.0);
        if (lo < 0) {
            lo = 0;
            hi = Math.max(lo, hi);
        }
        if (hi >= this.entityLists.length) {
            hi = this.entityLists.length - 1;
            lo = Math.min(lo, hi);
        }
        for (int s = lo; s <= hi; s++) {
            List l = this.entityLists[s];
            for (int i = 0; i < l.size(); i++) {
                Entity e = (Entity) l.get(i);
                if (e != self && e.boundingBox.intersectsWith(bb) && (sel == null || sel.isEntityApplicable(e))) {
                    out.add(e);
                    Entity[] parts = e.getParts();
                    if (parts != null) {
                        for (int k = 0; k < parts.length; k++) {
                            Entity p = parts[k];
                            if (p != self && p.boundingBox.intersectsWith(bb) && (sel == null || sel.isEntityApplicable(p))) {
                                out.add(p);
                            }
                        }
                    }
                }
            }
        }
    }

    private void fml$vanillaType(Class cls, AxisAlignedBB bb, List out, IEntitySelector sel) {
        int lo = MathHelper.floor_double((bb.minY - 2.0) / 16.0);
        int hi = MathHelper.floor_double((bb.maxY + 2.0) / 16.0);
        if (lo < 0) {
            lo = 0;
        } else if (lo >= this.entityLists.length) {
            lo = this.entityLists.length - 1;
        }
        if (hi >= this.entityLists.length) {
            hi = this.entityLists.length - 1;
        } else if (hi < 0) {
            hi = 0;
        }
        for (int s = lo; s <= hi; s++) {
            List l = this.entityLists[s];
            for (int i = 0; i < l.size(); i++) {
                Entity e = (Entity) l.get(i);
                if (cls.isAssignableFrom(e.getClass()) && e.boundingBox.intersectsWith(bb) && (sel == null || sel.isEntityApplicable(e))) {
                    out.add(e);
                }
            }
        }
    }
}
