package net.xiaoyu233.fml.vulkan.render.particle;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.AxisAlignedBB;
import net.minecraft.ClippingHelper;
import net.minecraft.ClippingHelperImpl;
import net.minecraft.Entity;
import net.minecraft.EntityBoat;
import net.minecraft.EntityBreakingFX;
import net.minecraft.EntityDiggingFX;
import net.minecraft.EntityFX;
import net.minecraft.EntityFireworkSparkFX;
import net.minecraft.EntityMinecart;
import net.minecraft.World;
import net.xiaoyu233.fml.vulkan.VulkanConfig;

/**
 * Particle fast path (ARCHITECTURE-NATIVE 3.10; OPTIMIZATIONS ENT-01/02/06). Static, render/client thread only. Everything here is
 * behaviour-preserving relative to MITE's {@code EffectRenderer}; the whole class is bypassed when {@code fml.vulkan.particles.fast=false}
 * (the mixins then run the MITE algorithms: O(n^2) removal, full collision, no culling).
 */
public final class ParticleFastPath {
    /** Per-layer cap, drop-oldest (MITE {@code EffectRenderer.addEffect}). */
    public static final int LAYER_CAP = 4000;

    private ParticleFastPath() {
    }

    /**
     * {@code fml.vulkan.particles.fast} latched once per frame by {@link #beginFrame()}: the hot paths (per addEffect, per particle per tick in
     * move) read this field instead of the system property, and a runtime flip cannot happen in the middle of an update pass.
     */
    private static boolean fastLatched = VulkanConfig.particlesFast();

    public static boolean enabled() {
        return fastLatched;
    }

    /** Duck interface implemented by {@code EntityFXParticleMixin} on every {@code EntityFX}. */
    public interface FxAccess {
        float vkp$scale();

        boolean vkp$prevPosInitialized();

        void vkp$initPrevPos();

        boolean vkp$inWeb();

        void vkp$setInWeb(boolean v);

        void vkp$fallState(double dy, boolean onGround);

        void vkp$blockCollisions();
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // ENT-02: container + update compaction. The core works on plain lists and an Ops adapter so it can be self-checked without a World.
    // ------------------------------------------------------------------------------------------------------------------------------

    /** What the update loop needs from a particle. */
    interface Ops {
        void update(Object p);

        boolean isDead(Object p);
    }

    private static final Ops FX_OPS = new Ops() {
        @Override
        public void update(Object p) {
            ((EntityFX) p).onUpdate();
        }

        @Override
        public boolean isDead(Object p) {
            return ((EntityFX) p).isDead;
        }
    };

    // State of the compaction pass in progress: list being updated, write index and index of the particle whose onUpdate is running.
    // Entries [passW, passR) of the list are stale duplicates/dead ones; addEffect during onUpdate removes them first (materialize).
    @SuppressWarnings("rawtypes")
    private static List passList;
    private static int passW;
    private static int passR;
    private static boolean passChanged;

    /**
     * ENT-02. MITE {@code addEffect}: when the layer already holds {@link #LAYER_CAP} particles the oldest is removed, then the new one is
     * appended. Must keep MITE's index semantics (the update loop may call this from inside {@code onUpdate}, firework sparks).
     */
    @SuppressWarnings({"rawtypes"})
    public static void addEffect(List[] layers, EntityFX fx) {
        addCore(layers[fx.getFXLayer()], fx, LAYER_CAP);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void addCore(List list, Object fx, int cap) {
        if (list == passList) {
            // an onUpdate of the running pass adds to its own layer: turn the compacted-in-place list into exactly what MITE's list is now
            if (passW < passR) {
                list.subList(passW, passR).clear();
                passR = passW;
            }
            passChanged = true;
        }
        if (list.size() >= cap) {
            list.remove(0);
        }
        list.add(fx);
    }

    /**
     * ENT-02. Replacement of {@code EffectRenderer.updateEffects}: update every particle of the four layers in order and remove dead ones
     * in a single stable pass (no {@code remove(index--)}). Particles added during the pass (by {@code onUpdate}) are visited exactly as
     * MITE visits them, and the eviction quirk of a full layer is preserved: a layer whose particle calls {@code addEffect} finishes with
     * MITE's own index loop.
     */
    @SuppressWarnings("rawtypes")
    public static void updateEffects(List[] layers) {
        for (int l = 0; l < 4; l++) {
            updateLayer(layers[l], FX_OPS);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void updateLayer(List list, Ops ops) {
        if (!(list instanceof ArrayList)) {
            mite(list, ops, 0);
            return;
        }
        int w = 0;
        int r = 0;
        boolean changed = false;
        passList = list;
        try {
            for (; r < list.size(); r++) {
                Object p = list.get(r);
                passW = w;
                passR = r;
                ops.update(p);
                if (passChanged) {
                    changed = true;
                    r = passR; // the particle now sits at its MITE index (== w)
                    passList = null;
                    passChanged = false;
                    if (ops.isDead(p)) {
                        list.remove(r--);
                    }
                    mite(list, ops, r + 1);
                    return;
                }
                if (!ops.isDead(p)) {
                    if (w != r) {
                        list.set(w, p);
                    }
                    w++;
                }
            }
        } catch (Throwable t) {
            // keep the list consistent: drop the stale region. Skip it when addCore already compacted the list in this pass
            // (passChanged) or the pass was already handed to MITE's loop (changed); the region is then gone or live.
            if (!changed && !passChanged && w < r) {
                list.subList(w, r).clear();
            }
            throw t;
        } finally {
            passList = null;
            passChanged = false;
        }
        if (w < list.size()) {
            list.subList(w, list.size()).clear();
        }
    }

    /** MITE's loop verbatim, starting at {@code from}. */
    @SuppressWarnings("rawtypes")
    private static void mite(List list, Ops ops, int from) {
        for (int i = from; i < list.size(); i++) {
            Object p = list.get(i);
            ops.update(p);
            if (ops.isDead(p)) {
                list.remove(i--);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // ENT-06: frustum cull
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final float CULL_MARGIN = 0.25F;
    private static final double SQRT2 = Math.sqrt(2.0);
    /** frustum planes (a,b,c,d) x 6, normalised, camera relative space (what ClippingHelper holds). */
    private static final float[] PLANES = new float[24];
    private static boolean frustumValid;

    /** Reader of the unscaled base size of classes that derive {@code particleScale} from the age (scale never exceeds it). */
    private interface ScaleBound {
        float of(EntityFX fx);
    }

    private static final ScaleBound USE_FIELD = null; // marker: classes whose particleScale is not rewritten at render time

    private static final ClassValue<Object> CULL_INFO = new ClassValue<>() {
        @Override
        protected Object computeValue(Class<?> c) {
            return computeCullInfo(c);
        }
    };
    private static final Object NOT_CULLABLE = new Object();
    private static final Object FIXED_SCALE = new Object();
    /**
     * Fixed scale, but renderParticle is overridden and never calls {@code EntityFX.renderParticle}: it reads prevPos directly and never
     * initialises prev_pos_initialized. Cull must interpolate from prevPos (as MITE does) and must not initialise it.
     */
    private static final Object FIXED_SCALE_OWN_RENDER = new Object();

    private static Object computeCullInfo(Class<?> c) {
        if (c == EntityFX.class) {
            return FIXED_SCALE;
        }
        if (c == EntityDiggingFX.class || c == EntityBreakingFX.class) {
            return FIXED_SCALE_OWN_RENDER;
        }
        String n = c.getName();
        if (!n.startsWith("net.minecraft.") || c.getSuperclass() != EntityFX.class) {
            return NOT_CULLABLE;
        }
        switch (n.substring("net.minecraft.".length())) {
            // renderParticle not overridden: scale fixed at construction
            case "EntityBubbleFX", "EntityAuraFX", "EntityDropParticleFX", "EntityEnchantmentTableParticleFX", "EntityExplodeFX",
                 "EntityRainFX", "EntityRepairFX", "EntitySuspendFX", "EntitySpellParticleFX":
                return FIXED_SCALE;
            // renderParticle rewrites particleScale = base * f(age), f <= 1, then calls super (one quad)
            case "EntityCloudFX":
                return scaleField(c, "field_70569_a");
            case "EntityCritFX":
                return scaleField(c, "initialParticleScale");
            case "EntityHeartFX":
                return scaleField(c, "particleScaleOverTime");
            case "EntityNoteFX":
                return scaleField(c, "noteParticleScale");
            case "EntityReddustFX", "EntitySacredFX":
                return scaleField(c, "reddustParticleScale");
            case "EntitySnowShovelFX":
                return scaleField(c, "snowDigParticleScale");
            case "EntitySmokeFX":
                return scaleField(c, "smokeParticleScale");
            case "EntityFlameFX":
                return scaleField(c, "flameScale");
            case "EntityLavaFX":
                return scaleField(c, "lavaParticleScale");
            case "EntityPortalFX":
                return scaleField(c, "portalParticleScale");
            default:
                return NOT_CULLABLE; // FootStep, LargeExplode, HugeExplode, Pickup, Crit2, Firework*, mod classes
        }
    }

    private static Object scaleField(Class<?> c, String field) {
        try {
            VarHandle vh = MethodHandles.privateLookupIn(c, MethodHandles.lookup()).findVarHandle(c, field, float.class);
            ScaleBound sb = fx -> (float) vh.get(fx);
            return sb;
        } catch (Throwable t) {
            return NOT_CULLABLE;
        }
    }

    /**
     * ENT-06. True when the particle must be skipped by the renderer (outside the view frustum with a margin derived from its scale);
     * only for allow-listed classes ({@link #cullable}). The caller still runs the {@code prev_pos_initialized} initialisation.
     */
    public static boolean culled(EntityFX fx, float partialTicks) {
        if (!frustumValid || !(fx instanceof FxAccess acc)) {
            return false;
        }
        Object info = CULL_INFO.get(fx.getClass());
        float scale;
        if (info == FIXED_SCALE || info == FIXED_SCALE_OWN_RENDER) {
            scale = acc.vkp$scale();
        } else if (info instanceof ScaleBound sb) {
            scale = Math.max(sb.of(fx), acc.vkp$scale());
        } else {
            return false;
        }
        double px = fx.prevPosX;
        double py = fx.prevPosY;
        double pz = fx.prevPosZ;
        // EntityFX.renderParticle seeds prevPos = pos on first render; classes with their own renderParticle (digging/breaking) never do,
        // so for them prevPos is used as it is: MITE's onUpdate maintains it, and it is what their renderParticle interpolates from.
        if (info != FIXED_SCALE_OWN_RENDER && !acc.vkp$prevPosInitialized()) {
            px = fx.posX;
            py = fx.posY;
            pz = fx.posZ;
        }
        // same formulas as EntityFX.renderParticle (digging/breaking use the algebraically equal form; the margin covers the rounding)
        float x = (float) (fx.posX * partialTicks + px * (1.0 - partialTicks) - EntityFX.interpPosX);
        float y = (float) (fx.posY * partialTicks + py * (1.0 - partialTicks) - EntityFX.interpPosY);
        float z = (float) (fx.posZ * partialTicks + pz * (1.0 - partialTicks) - EntityFX.interpPosZ);
        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) {
            return false;
        }
        float radius = (float) (0.1 * Math.abs(scale) * SQRT2) + CULL_MARGIN;
        return outside(x, y, z, radius);
    }

    /** Point outside the (planes grown by radius): behind any plane by more than radius. Planes are normalised. */
    static boolean outside(float x, float y, float z, float radius) {
        final float[] p = PLANES;
        for (int i = 0; i < 24; i += 4) {
            if (p[i] * x + p[i + 1] * y + p[i + 2] * z + p[i + 3] < -radius) {
                return true;
            }
        }
        return false;
    }

    /** ENT-06 allow-list: classes whose {@code renderParticle} emits one camera facing quad around the interpolated position. */
    public static boolean cullable(Class<?> particleClass) {
        return CULL_INFO.get(particleClass) != NOT_CULLABLE;
    }

    /**
     * Does what {@code EntityFX.renderParticle} does first for a particle that is skipped: {@code prevPos = pos} once (on the first render).
     * Subtask A calls this for a culled particle so the interpolation of its later frames is identical to MITE.
     */
    public static void initPrevPos(EntityFX fx) {
        if (fx instanceof FxAccess acc && initsPrevPosWhenCulled(fx.getClass())) {
            acc.vkp$initPrevPos();
        }
    }

    /**
     * True when a culled particle of this class must have prev_pos_initialized set (its renderParticle goes through
     * {@code EntityFX.renderParticle}). Digging/breaking render on their own and must keep MITE's flag state.
     */
    public static boolean initsPrevPosWhenCulled(Class<?> particleClass) {
        return CULL_INFO.get(particleClass) != FIXED_SCALE_OWN_RENDER;
    }

    /** Called once per frame by the renderer before {@link #culled} is used: captures the frustum of the current camera. */
    public static void beginFrame() {
        fastLatched = VulkanConfig.particlesFast();
        frustumValid = false;
        if (!fastLatched) {
            return;
        }
        try {
            // the same helper RenderGlobal/EntityRenderer use for culling (Frustrum wraps it); getInstance() re-reads the current matrices
            ClippingHelper h = ClippingHelperImpl.getInstance();
            float[][] f = h.frustum;
            for (int i = 0; i < 6; i++) {
                float a = f[i][0];
                float b = f[i][1];
                float c = f[i][2];
                float d = f[i][3];
                if (Float.isNaN(a + b + c + d)) {
                    return;
                }
                PLANES[i * 4] = a;
                PLANES[i * 4 + 1] = b;
                PLANES[i * 4 + 2] = c;
                PLANES[i * 4 + 3] = d;
            }
            frustumValid = true;
        } catch (Throwable t) {
            frustumValid = false;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // ENT-01: block-only move
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final ClassValue<Boolean> SIMPLE_MOVER = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return declarer(c, "moveEntity", double.class, double.class, double.class) == Entity.class
                && declarer(c, "canTriggerWalking") == EntityFX.class
                && declarer(c, "getCollisionBox", Entity.class) == Entity.class
                && declarer(c, "isDecoy") == Entity.class
                && declarer(c, "isEntityFX") == Entity.class;
        }
    };

    /** true when entities of this class may contribute a bounding box to {@code World.getCollidingBoundingBoxes} for a particle. */
    private static final ClassValue<Boolean> ADDS_BOX = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            Class<?> d = declarer(c, "getBoundingBox");
            // MITE classes that return null: Entity, EntityMinecart, EntityFireworkSparkFX. EntityBoat (and anything else) returns a box.
            return !(d == Entity.class || d == EntityMinecart.class || d == EntityFireworkSparkFX.class) || d == EntityBoat.class;
        }
    };

    private static World scanWorld;
    private static long scanTime = Long.MIN_VALUE;
    private static int scanCount = -1;
    private static boolean scanClean;

    private static Class<?> declarer(Class<?> c, String name, Class<?>... params) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(name, params);
                return c;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * No loaded entity contributes a box: the entity scan of {@code getCollidingBoundingBoxes} would add nothing. Rescanned once per tick,
     * and also whenever the loaded entity count changes, so an entity spawned after the scan in the same tick is picked up at once. The
     * only remaining gap is a same-tick removal plus addition that leaves the count unchanged; that is still bounded to the current tick.
     */
    @SuppressWarnings("rawtypes")
    private static boolean noEntityBoxes(World w) {
        long t = w.getTotalWorldTime();
        List all = w.loadedEntityList;
        int n = all.size();
        if (w != scanWorld || t != scanTime || n != scanCount) {
            scanWorld = w;
            scanTime = t;
            scanCount = n;
            boolean clean = true;
            Class<?> last = null;
            for (int i = 0; i < n; i++) {
                Class<?> c = all.get(i).getClass();
                if (c != last) {
                    last = c;
                    if (ADDS_BOX.get(c)) {
                        clean = false;
                        break;
                    }
                }
            }
            scanClean = clean;
        }
        return scanClean;
    }

    private static final double[] LIMITS = new double[3];

    /**
     * ENT-01. Replacement of the {@code moveEntity(dx, dy, dz)} call in {@code EntityFX.onUpdate}: identical results to
     * {@code Entity.moveEntity}, but the entity scan of {@code World.getCollidingBoundingBoxes} is skipped while no loaded entity overrides
     * {@code getBoundingBox()} other than the MITE ones. Falls back to {@code fx.moveEntity} otherwise or when {@link #enabled()} is false.
     */
    public static void move(EntityFX fx, double dx, double dy, double dz) {
        if (!enabled() || fx.stepHeight > 0.0F || !(fx instanceof FxAccess acc) || !SIMPLE_MOVER.get(fx.getClass())
            || !noEntityBoxes(fx.worldObj)) {
            fx.moveEntity(dx, dy, dz);
            return;
        }
        moveBlocksOnly(fx, acc, dx, dy, dz);
    }

    /** {@code Entity.moveEntity} for an {@code EntityFX} without step height, with {@code getCollidingBlockBounds} as collision source. */
    @SuppressWarnings("rawtypes")
    private static void moveBlocksOnly(EntityFX fx, FxAccess acc, double dx, double dy, double dz) {
        World w = fx.worldObj;
        AxisAlignedBB bb = fx.boundingBox;
        if (fx.posX + dx < w.min_entity_pos_xz) {
            dx = w.min_entity_pos_xz - fx.posX;
        } else if (fx.posX + dx > w.max_entity_pos_xz) {
            dx = w.max_entity_pos_xz - fx.posX;
        }
        if (fx.posY + dy < -255.0) {
            dy = -255.0 - fx.posY;
        } else if (fx.posY + dy > 255.0) {
            dy = 255.0 - fx.posY;
        }
        if (fx.posZ + dz < w.min_entity_pos_xz) {
            dz = w.min_entity_pos_xz - fx.posZ;
        } else if (fx.posZ + dz > w.max_entity_pos_xz) {
            dz = w.max_entity_pos_xz - fx.posZ;
        }

        if (fx.noClip) {
            bb.offset(dx, dy, dz);
            fx.posX = (bb.minX + bb.maxX) / 2.0;
            fx.posY = bb.minY + fx.yOffset - fx.ySize;
            fx.posZ = (bb.minZ + bb.maxZ) / 2.0;
            return;
        }

        fx.ySize *= 0.4F;
        double ox = fx.posX;
        double oy = fx.posY;
        double oz = fx.posZ;
        if (acc.vkp$inWeb()) {
            acc.vkp$setInWeb(false);
            dx *= 0.25;
            dy *= 0.05F;
            dz *= 0.25;
            fx.motionX = 0.0;
            fx.motionY = 0.0;
            fx.motionZ = 0.0;
        }

        final double reqX = dx;
        final double reqY = dy;
        final double reqZ = dz;
        List list = w.getCollidingBlockBounds(bb.addCoord(dx, dy, dz), fx);
        final double[] lim = LIMITS;
        final int n = list.size();
        lim[0] = Double.NaN;
        lim[1] = Double.NaN;
        lim[2] = 0.0;

        for (int i = 0; i < n; i++) {
            dy = ((AxisAlignedBB) list.get(i)).calculateYOffset(bb, dy, lim);
        }
        if (!applyLimits(bb, lim, 1, fx.height)) {
            bb.offset(0.0, dy, 0.0);
        }
        if (!fx.field_70135_K && reqY != dy) {
            dz = 0.0;
            dy = 0.0;
            dx = 0.0;
        }

        lim[0] = Double.NaN;
        lim[1] = Double.NaN;
        for (int i = 0; i < n; i++) {
            dx = ((AxisAlignedBB) list.get(i)).calculateXOffset(bb, dx, lim);
        }
        if (!applyLimits(bb, lim, 0, fx.width)) {
            bb.offset(dx, 0.0, 0.0);
        }
        if (!fx.field_70135_K && reqX != dx) {
            dz = 0.0;
            dy = 0.0;
            dx = 0.0;
        }

        lim[0] = Double.NaN;
        lim[1] = Double.NaN;
        for (int i = 0; i < n; i++) {
            dz = ((AxisAlignedBB) list.get(i)).calculateZOffset(bb, dz, lim);
        }
        if (!applyLimits(bb, lim, 2, fx.width)) {
            bb.offset(0.0, 0.0, dz);
        }
        if (!fx.field_70135_K && reqZ != dz) {
            dz = 0.0;
            dy = 0.0;
            dx = 0.0;
        }

        // (step-height block skipped: stepHeight <= 0 is checked by the caller)
        fx.posX = (bb.minX + bb.maxX) / 2.0;
        fx.posY = bb.minY + fx.yOffset - fx.ySize;
        fx.posZ = (bb.minZ + bb.maxZ) / 2.0;
        fx.isCollidedHorizontally = reqX != dx || reqZ != dz;
        fx.isCollidedVertically = reqY != dy;
        fx.onGround = reqY != dy && reqY < 0.0;
        fx.isCollided = fx.isCollidedHorizontally || fx.isCollidedVertically;
        acc.vkp$fallState(dy, fx.onGround);
        if (reqX != dx) {
            fx.motionX = 0.0;
        }
        if (reqY != dy) {
            fx.motionY = 0.0;
        }
        if (reqZ != dz) {
            fx.motionZ = 0.0;
        }
        // canTriggerWalking() is false for every class SIMPLE_MOVER accepts
        acc.vkp$blockCollisions();
    }

    /** {@code Entity.applyLimitsForX/Y/Z}; axis 0=X 1=Y 2=Z. */
    private static boolean applyLimits(AxisAlignedBB b, double[] l, int axis, float size) {
        if (!Entity.apply_MITE_bb_limits_checking) {
            return false;
        }
        if (Double.isNaN(l[0]) && Double.isNaN(l[1])) {
            return false;
        }
        if (!Double.isNaN(l[0])) {
            switch (axis) {
                case 0 -> {
                    l[2] = l[0] - b.maxX;
                    b.maxX = l[0];
                    b.minX = l[0] - size;
                }
                case 1 -> {
                    l[2] = l[0] - b.maxY;
                    b.maxY = l[0];
                    b.minY = l[0] - size;
                }
                default -> {
                    l[2] = l[0] - b.maxZ;
                    b.maxZ = l[0];
                    b.minZ = l[0] - size;
                }
            }
        } else {
            switch (axis) {
                case 0 -> {
                    l[2] = l[1] - b.minX;
                    b.minX = l[1];
                    b.maxX = l[1] + size;
                }
                case 1 -> {
                    l[2] = l[1] - b.minY;
                    b.minY = l[1];
                    b.maxY = l[1] + size;
                }
                default -> {
                    l[2] = l[1] - b.minZ;
                    b.minZ = l[1];
                    b.maxZ = l[1] + size;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Self checks (no test infrastructure in the repo): ParticleFastPath.selfCheck(n, seed) == null means success.
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final class Fake {
        final int id;
        int age;
        final int maxAge;
        final int spawns; // children spawned per update while alive (firework-like)
        final int layer;
        boolean dead;

        Fake(int id, int maxAge, int spawns, int layer) {
            this.id = id;
            this.maxAge = maxAge;
            this.spawns = spawns;
            this.layer = layer;
        }
    }

    /** Drives one world of 4 layers either with the fast core or with MITE's loop; deterministic from the seed. */
    private static final class Sim implements Ops {
        final List<Fake>[] layers;
        final boolean fast;
        final int cap;
        final Random rnd;
        int nextId;
        int budget = 200000;

        @SuppressWarnings("unchecked")
        Sim(boolean fast, int cap, long seed) {
            this.fast = fast;
            this.cap = cap;
            this.rnd = new Random(seed);
            this.layers = new List[4];
            for (int i = 0; i < 4; i++) {
                layers[i] = new ArrayList<>();
            }
        }

        Fake spawn(int maxAgeBound, boolean allowSpawner) {
            int spawns = allowSpawner && rnd.nextInt(10) == 0 ? 1 + rnd.nextInt(3) : 0;
            return new Fake(nextId++, 1 + rnd.nextInt(maxAgeBound), spawns, rnd.nextInt(4));
        }

        @SuppressWarnings("unchecked")
        void add(Fake f) {
            List<Fake> l = layers[f.layer];
            if (fast) {
                addCore(l, f, cap);
            } else {
                if (l.size() >= cap) {
                    l.remove(0);
                }
                l.add(f);
            }
        }

        @Override
        public void update(Object p) {
            Fake f = (Fake) p;
            if (f.age++ >= f.maxAge) {
                f.dead = true;
            }
            if (!f.dead && f.spawns > 0 && budget > 0) {
                for (int i = 0; i < f.spawns; i++) {
                    budget--;
                    add(spawn(30, false));
                }
            }
        }

        @Override
        public boolean isDead(Object p) {
            return ((Fake) p).dead;
        }

        void tick() {
            for (int l = 0; l < 4; l++) {
                if (fast) {
                    updateLayer(layers[l], this);
                } else {
                    mite(layers[l], this, 0);
                }
            }
        }
    }

    /**
     * Randomised equivalence check of the ENT-02 compaction against MITE's loop: {@code n} seeded particles per run (some of them spawn
     * children from {@code update}, i.e. addEffect inside the pass, with a small cap so the eviction quirk triggers), 600 ticks, layer
     * contents compared after every tick. Variant 2 has lifetimes up to 900 ticks, so particles survive the whole run. Also runs
     * {@link #cullSelfCheck}. Returns null on success, else a description of the first mismatch.
     */
    public static String selfCheck(int n, long seed) {
        int[] caps = {LAYER_CAP, 64, 7};
        for (int cap : caps) {
            for (int variant = 0; variant < 3; variant++) {
                long s = seed * 31 + variant;
                int bound = variant == 2 ? 900 : 80;
                Sim a = new Sim(true, cap, s);
                Sim b = new Sim(false, cap, s);
                for (int i = 0; i < n; i++) {
                    boolean spawner = variant > 0;
                    Fake fa = a.spawn(bound, spawner);
                    Fake fb = b.spawn(bound, spawner);
                    a.add(fa);
                    b.add(fb);
                }
                for (int t = 0; t < 600; t++) {
                    if (t % 50 == 0) {
                        for (int i = 0; i < n / 10; i++) {
                            a.add(a.spawn(60, variant > 0));
                            b.add(b.spawn(60, variant > 0));
                        }
                    }
                    a.tick();
                    b.tick();
                    for (int l = 0; l < 4; l++) {
                        List<Fake> x = a.layers[l];
                        List<Fake> y = b.layers[l];
                        if (x.size() != y.size()) {
                            return "cap=" + cap + " variant=" + variant + " tick=" + t + " layer=" + l + " size " + x.size() + " != " + y.size();
                        }
                        for (int i = 0; i < x.size(); i++) {
                            Fake p = x.get(i);
                            Fake q = y.get(i);
                            if (p.id != q.id || p.age != q.age || p.dead != q.dead) {
                                return "cap=" + cap + " variant=" + variant + " tick=" + t + " layer=" + l + " index=" + i + " id " + p.id + " != " + q.id;
                            }
                        }
                    }
                }
            }
        }
        String cull = cullSelfCheck(seed);
        return cull == null ? null : "cull: " + cull;
    }

    /**
     * Margin test of the cull. A particle reported as culled must have no point of its bounding sphere (radius as used by
     * {@link #culled}) inside the frustum. Sampled on a grid inside the sphere, so it can miss a violation but never reports a false one.
     */
    static String cullSelfCheck(long seed) {
        float[] saved = PLANES.clone();
        try {
            // simple box frustum |x|,|y|,|z| <= 10 with inward normals
            float[][] pl = {{1, 0, 0, 10}, {-1, 0, 0, 10}, {0, 1, 0, 10}, {0, -1, 0, 10}, {0, 0, 1, 10}, {0, 0, -1, 10}};
            for (int i = 0; i < 6; i++) {
                System.arraycopy(pl[i], 0, PLANES, i * 4, 4);
            }
            Random r = new Random(seed);
            for (int i = 0; i < 20000; i++) {
                float x = (r.nextFloat() - 0.5F) * 30;
                float y = (r.nextFloat() - 0.5F) * 30;
                float z = (r.nextFloat() - 0.5F) * 30;
                float radius = (float) (0.1 * (r.nextFloat() * 4) * SQRT2) + CULL_MARGIN;
                if (!outside(x, y, z, radius)) {
                    continue;
                }
                for (int a = -4; a <= 4; a++) {
                    for (int b = -4; b <= 4; b++) {
                        for (int c = -4; c <= 4; c++) {
                            float dx = radius * a / 4F;
                            float dy = radius * b / 4F;
                            float dz = radius * c / 4F;
                            if (dx * dx + dy * dy + dz * dz > radius * radius) {
                                continue;
                            }
                            float px = x + dx;
                            float py = y + dy;
                            float pz = z + dz;
                            if (Math.abs(px) <= 10 && Math.abs(py) <= 10 && Math.abs(pz) <= 10) {
                                return "culled a particle whose sphere reaches the frustum at " + x + "," + y + "," + z + " radius " + radius;
                            }
                        }
                    }
                }
            }
            return null;
        } finally {
            System.arraycopy(saved, 0, PLANES, 0, 24);
        }
    }
}
