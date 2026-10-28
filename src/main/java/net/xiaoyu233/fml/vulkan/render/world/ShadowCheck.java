package net.xiaoyu233.fml.vulkan.render.world;

import net.minecraft.Entity;

/**
 * In-process gameplay-invariance check of 7.2 ({@code -Dfml.vulkan.shadowCheck=true}). Production code records the native decisions here
 * (cheap no-ops when disabled); the devtest source set installs a {@link Reference} (MITE's original predicate chain copied verbatim)
 * and a {@code mismatches()} reader. Render thread only.
 */
public final class ShadowCheck {
    /** Reference implementation lives in the devtest source set. */
    public interface Reference {
        /** MITE's original accept predicate for the entity (same inputs as {@link EntityVisibility#mitePasses}). */
        boolean entityPasses(Entity e, net.minecraft.Vec3 viewPos, net.minecraft.ICamera camera, net.minecraft.Minecraft mc, boolean renderOverride);

        /** Reference recomputation of getMouseOver's RaycastCollision (null for none) for the current frame. */
        net.minecraft.RaycastCollision mouseOver(net.minecraft.Minecraft mc, float partial);
    }

    private static volatile Reference reference;
    private static final boolean ENABLED = Boolean.getBoolean("fml.vulkan.shadowCheck");
    private static long mismatches;

    private ShadowCheck() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void setReference(Reference r) {
        reference = r;
    }

    public static Reference reference() {
        return reference;
    }

    private static final boolean CULL = Boolean.getBoolean("fml.vulkan.entityCull");

    private static long frameHash;
    private static int frameEntities;
    private static long frameMismatches;
    private static int frameLastTickWrites;
    private static long frameDrawHash;
    private static int frameDraws;

    public static void beginFrame() {
        if (!ENABLED) {
            return;
        }
        frameHash = 0L;
        frameEntities = 0;
        frameMismatches = 0L;
        frameLastTickWrites = 0;
        frameDrawHash = 0L;
        frameDraws = 0;
    }

    /**
     * Post-cull draw decision for an entity MITE accepted. Called once per accepted entity. The draw set is compared with the accepted
     * set in {@link #endFrame()} when culling is off; with culling on it is only logged (count and hash).
     */
    public static void onDrawDecision(Entity e, boolean drawn) {
        if (!ENABLED) {
            return;
        }
        if (drawn) {
            frameDraws++;
            frameDrawHash += mix(e.entityId);
        }
    }

    /** Native path accepted (true) / rejected the entity for the Packet87 + draw set. */
    public static void onEntityDecision(Entity e, boolean nativePasses, net.minecraft.Vec3 viewPos, net.minecraft.ICamera camera, boolean renderOverride) {
        if (!ENABLED) {
            return;
        }
        Reference r = reference;
        if (r != null) {
            boolean ref = r.entityPasses(e, viewPos, camera, net.minecraft.Minecraft.getMinecraft(), renderOverride);
            if (ref != nativePasses) {
                frameMismatches++;
            }
        }
        if (nativePasses) {
            frameEntities++;
            frameHash += mix(e.entityId);
        }
    }

    private static long mix(long v) {
        v += 0x9E3779B97F4A7C15L;
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    /** Hash of Minecraft.objectMouseOver: 0 for none, entity id, or block position + face-independent coords. */
    public static long mouseOverHash(net.minecraft.RaycastCollision rc) {
        if (rc == null) {
            return 0L;
        }
        if (rc.isEntity()) {
            return mix(1L + rc.getEntityHit().entityId);
        }
        if (rc.isBlock()) {
            return mix(((long) rc.block_hit_x * 31 + rc.block_hit_y) * 31 + rc.block_hit_z + 0x1234567L);
        }
        return 0L;
    }

    /** Direct comparison: entity id, or block xyz + face + metadata; null equals null. */
    static boolean sameHit(net.minecraft.RaycastCollision a, net.minecraft.RaycastCollision b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isEntity() || b.isEntity()) {
            return a.isEntity() && b.isEntity() && a.getEntityHit() != null && b.getEntityHit() != null
                    && a.getEntityHit().entityId == b.getEntityHit().entityId;
        }
        return a.isBlock() == b.isBlock() && a.block_hit_x == b.block_hit_x && a.block_hit_y == b.block_hit_y
                && a.block_hit_z == b.block_hit_z && a.face_hit == b.face_hit && a.block_hit_metadata == b.block_hit_metadata;
    }

    public static void onMouseOver(float partial) {
        if (!ENABLED) {
            return;
        }
        Reference r = reference;
        if (r == null) {
            return;
        }
        net.minecraft.Minecraft mc = net.minecraft.Minecraft.getMinecraft();
        if (!sameHit(r.mouseOver(mc, partial), mc.objectMouseOver)) {
            frameMismatches++;
        }
    }

    /**
     * Lower-bound count of {@code lastTickPos} writes of RenderManager.renderEntity / syncCulledEntity for an entity with
     * {@code ticksExisted == 0}: counted when the value changed across the call. A write that stores the value already held is not seen,
     * so this under-counts; the exact count needs a hook at RenderManager.renderEntity's write (NR-13 keep-hook).
     */
    public static void onLastTickPosWrite(Entity e, double prevX, double prevY, double prevZ) {
        if (ENABLED && (e.lastTickPosX != prevX || e.lastTickPosY != prevY || e.lastTickPosZ != prevZ)) {
            frameLastTickWrites++;
        }
    }

    /** Logs {@code hash(set)}, the post-cull draw hash and the per-frame counters; accumulates mismatches. */
    public static void endFrame() {
        if (!ENABLED) {
            return;
        }
        if (!CULL && (frameDraws != frameEntities || frameDrawHash != frameHash)) {
            frameMismatches++;
        }
        mismatches += frameMismatches;
        net.xiaoyu233.fml.vulkan.VkLog.LOGGER.info("ShadowCheck frame hash={} entities={} drawHash={} draws={} cull={} mismatches={} lastTickWrites(lower bound)={}",
                Long.toHexString(frameHash), frameEntities, Long.toHexString(frameDrawHash), frameDraws, CULL, frameMismatches, frameLastTickWrites);
    }

    public static long mismatches() {
        return mismatches;
    }
}
