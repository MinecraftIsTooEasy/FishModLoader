package net.xiaoyu233.fml.vulkan.testbot;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.Entity;
import net.minecraft.EntityFX;
import net.minecraft.EntityLivingBase;
import net.minecraft.Minecraft;
import net.minecraft.WorldClient;
import net.minecraft.WorldServer;
import net.xiaoyu233.fml.vulkan.VulkanConfig;

/**
 * Frozen-world mode (ARCHITECTURE-NATIVE 7.2, {@code -Pvk.frozen=true} = {@code fml.vulkan.frozen=true}): everything that makes two captures of the same scene differ
 * is switched off or pinned, so that a capture is a pure function of (build, scene). The switches are read by the bot mixins ({@code mixin/Bot*}), the scene script
 * flips the per scene parts:
 * <ul>
 * <li>server: no time progress, no random block ticks / ambiance ticks ({@code WorldServer.tickBlocksAndAmbiance}, {@code shouldTimeProgress}), game rules
 * {@code doDaylightCycle}/{@code doMobSpawning} off (set by the script), the client side ambiance tick and weather drift are skipped;</li>
 * <li>scene entities: {@link #freezeEntity} pins position, rotation and limb swing and removes the entity from entity ticking on both sides;</li>
 * <li>capture window: {@link #beginCapture} pauses the client tick ({@code Minecraft.runTick}: cloud counter, renderer update counter, texture animation, particles,
 * lightmap flicker, panorama timer all stand still), forces {@code Timer.renderPartialTicks} to one value and replaces {@code Minecraft.getSystemTime} (enchantment glint,
 * title bounce, achievement toast, ...) by a constant;</li>
 * <li>particles are spawned from a seeded {@link Random} ({@link #pinParticle}), weather is fixed by {@link #lockWeather}.</li>
 * </ul>
 * Outside {@link #active()} every hook is a no-op, so the bot behaves exactly as before in ordinary runs.
 */
public final class Frozen {
    /** The constant clock of a capture window (ms): every animation driven by {@code getSystemTime} (modulo 600 / 1000 / 3000 / 4873 ms) shows one fixed phase. */
    public static final long FAKE_CLOCK_MS = 86_400_000L;
    /** lcm of the animation periods 600, 1000, 3000 and 4873 ms: a clock with the same value modulo this gives the same phase of every animation. */
    private static final long PERIOD_LCM_MS = 14_619_000L;
    private static volatile long frozenClock = FAKE_CLOCK_MS;

    /**
     * The clock of a capture window: constant, with the same animation phase as {@link #FAKE_CLOCK_MS} on every run, but always BEHIND the real clock of the window
     * opening by less than two periods, so {@code Minecraft.debugUpdateTime} (the snooper / F3 bookkeeping) never runs ahead of it and no catch-up loop runs.
     */
    public static long clockMs() {
        return frozenClock;
    }

    private static long chooseClock(long realNow) {
        long base = realNow - Math.floorMod(realNow, PERIOD_LCM_MS) - PERIOD_LCM_MS + Math.floorMod(FAKE_CLOCK_MS, PERIOD_LCM_MS);
        return base < 0 ? FAKE_CLOCK_MS : base;
    }
    /** Default render partial tick of a capture window. */
    public static final float DEFAULT_PARTIAL_TICKS = 0.5F;
    /** World creation time shown to the client world during captures (any constant). */
    public static final long FIXED_CREATION_TIME = 1_700_000_000_000L;

    private static final boolean ACTIVE = VulkanConfig.frozen();
    private static final Set<Integer> FROZEN_ENTITIES = ConcurrentHashMap.newKeySet();

    private static volatile boolean atlasFrozen;
    private static volatile boolean ticksPaused;
    private static volatile boolean captureWindow;
    private static volatile float partialTicks = DEFAULT_PARTIAL_TICKS;
    private static volatile boolean weatherLocked;
    private static volatile int panoramaTimer = 40;

    /** The panorama timer shown by title-context scenes (set by the scene, re-applied inside every capture window). */
    public static void setPanoramaTimer(int ticks) {
        panoramaTimer = ticks;
    }

    private Frozen() {
    }

    /** True with {@code fml.vulkan.frozen=true}. */
    public static boolean active() {
        return ACTIVE;
    }

    // ------------------------------------------------------------------ hooks read by the mixins (any thread)

    /** Server and client ambiance / random block ticks and time progress are skipped. */
    public static boolean worldFrozen() {
        return ACTIVE;
    }

    /** The atlas animation tick ({@code TextureMap.tick}) is skipped: from {@link #freezeAtlas} on, during the whole capture route. */
    public static boolean atlasFrozen() {
        return ACTIVE && (atlasFrozen || ticksPaused);
    }

    /** {@code Minecraft.runTick} does nothing (the bot's own tick hook still runs). */
    public static boolean clientTicksPaused() {
        return ACTIVE && ticksPaused;
    }

    /** True while a capture window forces the partial tick and the clock. */
    public static boolean captureWindowOpen() {
        return ACTIVE && captureWindow;
    }

    public static float forcedPartialTicks() {
        return partialTicks;
    }

    /** The client world's weather drift ({@code WorldClient.updateWeather}) is skipped. */
    public static boolean weatherLocked() {
        return ACTIVE && weatherLocked;
    }

    /** True for entities pinned with {@link #freezeEntity} (their {@code updateEntityWithOptionalForce} is skipped, client and server). */
    public static boolean isFrozenEntity(Entity e) {
        return ACTIVE && e != null && !FROZEN_ENTITIES.isEmpty() && FROZEN_ENTITIES.contains(e.entityId);
    }

    // ------------------------------------------------------------------ scene API (script thread / bot thread; render state is touched on the client thread)

    /** Opens a capture window: ticks paused, partial tick forced, clock constant. Call from the client thread. */
    public static void beginCapture(float partial) {
        if (!ACTIVE) {
            return;
        }
        partialTicks = partial;
        if (!captureWindow) {
            frozenClock = chooseClock(net.minecraft.Minecraft.getSystemTime());
        }
        captureWindow = true;
        ticksPaused = true;
    }

    /**
     * Brings every animated sprite of the block and item atlases to its first frame (tick counter 0), then stops the atlas animation for the rest of the route, so the
     * texture state of a capture does not depend on how many client ticks ran before it. Client (render) thread. Sprites with their own {@code updateAnimation} (compass,
     * clock) are left alone. Returns the number of sprites that were advanced.
     */
    public static int freezeAtlas(Minecraft mc) {
        if (!ACTIVE) {
            return 0;
        }
        int advanced = 0;
        for (net.minecraft.ResourceLocation loc : new net.minecraft.ResourceLocation[] {net.minecraft.TextureMap.locationBlocksTexture, net.minecraft.TextureMap.locationItemsTexture}) {
            net.minecraft.TextureObject t = mc.getTextureManager().getTexture(loc);
            if (!(t instanceof net.minecraft.TextureMap map)) {
                continue;
            }
            Object list = getField(map, "listAnimatedSprites");
            if (!(list instanceof java.util.List<?> sprites)) {
                continue;
            }
            mc.getTextureManager().bindTexture(loc);
            for (Object o : sprites) {
                if (o.getClass() != net.minecraft.TextureAtlasSprite.class) {
                    continue;
                }
                net.minecraft.TextureAtlasSprite sprite = (net.minecraft.TextureAtlasSprite) o;
                int guard = 0;
                while ((((Integer) getField(sprite, "frameCounter")) != 0 || ((Integer) getField(sprite, "tickCounter")) != 0) && guard++ < 100_000) {
                    sprite.updateAnimation();
                    advanced++;
                }
            }
        }
        atlasFrozen = true;
        return advanced;
    }

    public static void releaseAtlas() {
        atlasFrozen = false;
    }

    /** Closes the capture window (ticks run again, real clock). */
    public static void endCapture() {
        captureWindow = false;
        ticksPaused = false;
    }

    /** Fixes the weather: the client world keeps {@code strength} (0 or 1) and the render view entity copy of it. Client thread. */
    public static void lockWeather(WorldClient world, float strength) {
        world.setRainStrength(strength);
        world.setRainStrengthForRenderViewEntity();
        weatherLocked = ACTIVE;
    }

    public static void unlockWeather() {
        weatherLocked = false;
    }

    /**
     * Pins an entity for a scene: positions of all tick generations equal, rotation/head/body yaw equal to its yaw, limb swing and swing progress zero, motion zero; its
     * id is registered so {@code updateEntityWithOptionalForce} (AI, movement, animation counters) is skipped on both sides. Call on the thread that owns the entity.
     */
    public static void freezeEntity(Entity e) {
        e.motionX = 0.0;
        e.motionY = 0.0;
        e.motionZ = 0.0;
        e.prevPosX = e.posX;
        e.prevPosY = e.posY;
        e.prevPosZ = e.posZ;
        e.lastTickPosX = e.posX;
        e.lastTickPosY = e.posY;
        e.lastTickPosZ = e.posZ;
        e.prevRotationYaw = e.rotationYaw;
        e.prevRotationPitch = e.rotationPitch;
        if (e instanceof EntityLivingBase l) {
            l.prevLimbSwingAmount = 0.0F;
            l.limbSwingAmount = 0.0F;
            l.limbSwing = 0.0F;
            l.prevSwingProgress = 0.0F;
            l.swingProgress = 0.0F;
            l.renderYawOffset = l.rotationYaw;
            l.prevRenderYawOffset = l.rotationYaw;
            l.rotationYawHead = l.rotationYaw;
            l.prevRotationYawHead = l.rotationYaw;
            l.hurtTime = 0;
        }
        FROZEN_ENTITIES.add(e.entityId);
    }

    /** Releases every pinned entity (end of a scene). */
    public static void releaseEntities() {
        FROZEN_ENTITIES.clear();
    }

    /**
     * Makes a freshly constructed particle independent of {@code Math.random()} / the entity's unseeded RNG (EntityFX's constructor draws its scale, texture jitter, life and
     * motion from them): the fields are overwritten with values from {@code rng}. Call right after construction on the client thread; the client tick is paused afterwards,
     * so the particle never moves before the capture.
     */
    public static void pinParticle(EntityFX p, Random rng) {
        setField(p, "particleScale", rng.nextFloat() + 1.0F);
        setField(p, "particleTextureJitterX", rng.nextFloat() * 3.0F);
        setField(p, "particleTextureJitterY", rng.nextFloat() * 3.0F);
        setField(p, "particleMaxAge", (int) (4.0F / (rng.nextFloat() * 0.9F + 0.1F)));
        setField(p, "particleAge", 0);
        p.motionX = 0.0;
        p.motionY = 0.0;
        p.motionZ = 0.0;
    }

    /** Sets a (possibly private, possibly inherited) field by name; used to pin counters (cloud tick counter, panorama timer, ...). */
    public static void setField(Object target, String name, Object value) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // try the superclass
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("cannot set " + c.getName() + "." + name + ": " + e, e);
            }
        }
        throw new IllegalStateException("no field " + name + " in " + target.getClass().getName());
    }

    /** Reads a (possibly private, possibly inherited) field by name; null when it does not exist. */
    public static Object getField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // try the superclass
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** Server side setup for a frozen run: game rules, weather rules and (through the mixins) no time progress. Call on the server thread. */
    public static void prepareServer(WorldServer[] worlds) {
        for (WorldServer w : worlds) {
            if (w != null) {
                try {
                    w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                    w.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
                } catch (Throwable ignored) {
                    // game rule API differs: not fatal, the mixins still stop time and random ticks
                }
            }
        }
    }

    /**
     * Pins everything a capture frame reads that the paused tick cannot stop, once per scene on the client thread: the cloud and renderer update counters, the torch
     * flicker of the lightmap (suppressed on the player and zeroed), the fog brightness and hand field-of-view smoothing, and the client world's clock, which drifts ahead of the frozen server time
     * between time packets ({@code WorldClient.tick} adds one per tick) and is therefore overwritten with the server's value of the same dimension.
     */
    public static void pinClientCounters(Minecraft mc) {
        if (mc.currentScreen instanceof net.minecraft.GuiMainMenu m) {
            setField(m, "panoramaTimer", panoramaTimer);
        }
        if (mc.renderGlobal != null) {
            setField(mc.renderGlobal, "cloudTickCounter", 1000);
            // the fancy cloud mesh is compiled relative to the viewer position of the LAST compile (history dependent float rounding): recompile at the capture pose
            setField(mc.renderGlobal, "clouds_recompiling_step", 0);
        }
        if (mc.entityRenderer != null) {
            setField(mc.entityRenderer, "rendererUpdateCount", 1000);
            for (String f : new String[] {"torchFlickerX", "torchFlickerY", "torchFlickerDX", "torchFlickerDY"}) {
                setField(mc.entityRenderer, f, 0.0F);
            }
            // smoothed values that converge a little more with every renderer tick: fog brightness (fogColor1/2) and the hand field of view
            setField(mc.entityRenderer, "fogColor1", 1.0F);
            setField(mc.entityRenderer, "fogColor2", 1.0F);
            setField(mc.entityRenderer, "fovModifierHand", 1.0F);
            setField(mc.entityRenderer, "fovModifierHandPrev", 1.0F);
            setField(mc.entityRenderer, "lightmapUpdateNeeded", true);
        }
        if (mc.thePlayer != null) {
            net.minecraft.EntityClientPlayerMP pl = mc.thePlayer;
            pl.torch_flicker_suppressed = true;
            // first person hand / camera bob smoothing converges a little more with every player tick and the swing / hurt state must be at rest
            pl.prevRenderArmYaw = pl.renderArmYaw = pl.rotationYaw;
            pl.prevRenderArmPitch = pl.renderArmPitch = pl.rotationPitch;
            pl.prevCameraYaw = pl.cameraYaw = 0.0F;
            pl.prevCameraPitch = pl.cameraPitch = 0.0F;
            pl.prevDistanceWalkedModified = pl.distanceWalkedModified = 0.0F;
            pl.prevSwingProgress = pl.swingProgress = 0.0F;
            if (mc.theWorld != null) {
                // World.getSkyColor moves this value by 0.005 towards the biome temperature on EVERY call (every rendered frame): start from the target
                mc.theWorld.biome_temperature_transition_for_sky_color = mc.theWorld
                    .getBiomeGenForCoords(net.minecraft.MathHelper.floor_double(mc.thePlayer.posX), net.minecraft.MathHelper.floor_double(mc.thePlayer.posZ)).getFloatTemperature();
            }
        }
        if (mc.theWorld != null) {
            // MITE stamps a new world with System.currentTimeMillis(); sunrise/sunset colours and the weather forecast are seeded with it
            mc.theWorld.getWorldInfo().setWorldCreationTime(FIXED_CREATION_TIME);
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            WorldServer w = server == null ? null : server.worldServerForDimension(mc.theWorld.getDimensionId());
            if (w != null) {
                mc.theWorld.setTotalWorldTime(w.getTotalWorldTime());
            }
        }
    }

    /**
     * One line with every client state value a frame reads that can drift (world time, celestial angle, sun brightness, rain, fog / flicker / fov smoothing, lightmap hash,
     * counters). Written next to every capture of a scene so that two captures that are not identical can be explained by the first value that differs.
     */
    public static String fingerprint(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        net.minecraft.WorldClient w = mc.theWorld;
        if (w != null) {
            sb.append("time=").append(w.getTotalWorldTime()).append(" tod=").append(w.getTimeOfDay()).append(" angle=").append(w.getCelestialAngle(0.5F)).append(" sun=")
                .append(w.getSunBrightness(1.0F)).append(" rain=").append(w.getRainStrength(0.5F)).append(" skytemp=").append(w.biome_temperature_transition_for_sky_color).append(" skysub=").append(w.skylightSubtracted).append(" lightning=")
                .append(w.lastLightningBolt);
        }
        if (mc.entityRenderer != null) {
            Object lm = getField(mc.entityRenderer, "lightmapColors");
            sb.append(" fog1=").append(getField(mc.entityRenderer, "fogColor1")).append(" fog2=").append(getField(mc.entityRenderer, "fogColor2")).append(" flick=")
                .append(getField(mc.entityRenderer, "torchFlickerX")).append(" fov=").append(getField(mc.entityRenderer, "fovModifierHand")).append(" upd=")
                .append(getField(mc.entityRenderer, "rendererUpdateCount")).append(" lightmap=").append(lm instanceof int[] a ? java.util.Arrays.hashCode(a) : "?");
        }
        if (mc.thePlayer != null) {
            sb.append(" pos=").append(mc.thePlayer.posX).append(',').append(mc.thePlayer.posY).append(',').append(mc.thePlayer.posZ).append(" rot=").append(mc.thePlayer.rotationYaw)
                .append(',').append(mc.thePlayer.rotationPitch).append(" cam=").append(mc.thePlayer.cameraYaw).append(',').append(mc.thePlayer.cameraPitch).append(" walked=")
                .append(mc.thePlayer.distanceWalkedModified);
        }
        if (mc.renderGlobal != null) {
            sb.append(" cloud=").append(getField(mc.renderGlobal, "cloudTickCounter"));
        }
        sb.append(" partial=").append(partialTicks).append(" fps=").append(Minecraft.last_fps);
        return sb.toString();
    }
}
