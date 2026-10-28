package net.xiaoyu233.fml.vulkan.opt;

import net.xiaoyu233.fml.vulkan.mixin.opt.OptMixinPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * WP-18 (ENT-07): values of the independent quality switches. Every value is a system property (also settable as a key of
 * {@code config/fml-vulkan.properties}); defaults reproduce vanilla MITE behaviour. The whole area is gated by
 * {@link OptMixinPlugin#areaActive(String) area "quality"}, so ConflictGuard (Better Game Setting) turns every switch off.
 */
public final class QualitySwitches {
    /** {@code true} (default): entity shadows drawn in fancy graphics (MITE behaviour); {@code false}: no shadow quads. */
    public static final String PROP_ENTITY_SHADOWS = "fml.vulkan.quality.entityShadows";
    /** Entity render distance multiplier, 0.5 to 5.0, default 1.0 (MITE behaviour). */
    public static final String PROP_DISTANCE_SCALE = "fml.vulkan.quality.distanceScale";
    public static final double MIN_DISTANCE_SCALE = 0.5;
    public static final double MAX_DISTANCE_SCALE = 5.0;

    private static final Logger LOGGER = LogManager.getLogger("FML-Quality");
    /** Parsed once; the raw property is read on first use (after the launcher has set the properties). */
    private static volatile Double distanceScaleCache;

    private QualitySwitches() {
    }

    /** True when the entity shadow quads are drawn. Default true; false when the quality area is off. */
    public static boolean shadowsEnabled() {
        return OptMixinPlugin.areaActive("quality") && OptFlags.bool(PROP_ENTITY_SHADOWS, true);
    }

    /**
     * Multiplier of the entity render distance (1.0 when the quality area is off). Applied to the render path only
     * ({@code Entity.isInRangeToRenderVec3D}), never to gameplay range checks.
     */
    public static double distanceScale() {
        if (!OptMixinPlugin.areaActive("quality")) {
            return 1.0;
        }
        Double cached = distanceScaleCache;
        if (cached == null) {
            cached = resolveDistanceScale();
            distanceScaleCache = cached;
        }
        return cached;
    }

    private static double resolveDistanceScale() {
        double raw = OptFlags.decimal(PROP_DISTANCE_SCALE, 1.0);
        if (Double.isNaN(raw)) {
            LOGGER.warn("{}: not a number, using 1.0", PROP_DISTANCE_SCALE);
            return 1.0;
        }
        double clamped = Math.max(MIN_DISTANCE_SCALE, Math.min(MAX_DISTANCE_SCALE, raw));
        if (clamped != raw) {
            LOGGER.warn("{}={} is outside [{}, {}], using {}", PROP_DISTANCE_SCALE, raw, MIN_DISTANCE_SCALE, MAX_DISTANCE_SCALE, clamped);
        }
        LOGGER.info("Entity distance scale {}", clamped);
        return clamped;
    }
}
