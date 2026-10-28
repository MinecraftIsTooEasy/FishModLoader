package net.xiaoyu233.fml.vulkan.testbot;

import java.lang.reflect.Method;

/**
 * Terrain activity signals that survive the phase-1 to native terrain switch (ARCHITECTURE-NATIVE 7.2: a scene is quiet when the terrain queue is empty and no section
 * was committed for 20 frames). Native terrain (NR-09/10) is read reflectively through the optional statics
 * {@code TerrainRenderer.pendingSections()} (queued plus in flight meshes, int) and {@code TerrainRenderer.sectionCommits()} (monotonic commit counter, long);
 * they are a contract request. Without them the phase-1 fields are the only source, and when those are gone the value is UNKNOWN (-1) which callers treat as NOT quiet.
 */
final class TerrainSignals {
    private static final Method PENDING = method("pendingSections");
    private static final Method COMMITS = method("sectionCommits");

    private TerrainSignals() {
    }

    private static Method method(String name) {
        try {
            return Class.forName("net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer").getMethod(name);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    static boolean nativeCounters() {
        return PENDING != null;
    }

    /** Queued plus in flight meshes of the native terrain; -1 when the build has no such counter. */
    static int nativePending() {
        if (PENDING == null) {
            return -1;
        }
        try {
            return ((Number) PENDING.invoke(null)).intValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /** Monotonic count of committed sections; -1 when unavailable. */
    static long nativeCommits() {
        if (COMMITS == null) {
            return -1;
        }
        try {
            return ((Number) COMMITS.invoke(null)).longValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }
}
