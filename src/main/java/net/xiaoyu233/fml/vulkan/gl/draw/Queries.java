package net.xiaoyu233.fml.vulkan.gl.draw;

import net.xiaoyu233.fml.vulkan.gl.GLErrors;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.nio.IntBuffer;

/**
 * Occlusion/timer query objects (GL 1.5 / ARB_occlusion_query). The port reports occlusion queries as unsupported, so MITE never
 * uses them; the objects are accepted for compatibility and every result is "available" and "visible" (1 sample passed), 0 ns for
 * timer queries. Query functions are never compiled into display lists. Render thread only.
 */
public final class Queries {
    private static final int TARGETS = 3;
    private static final int MAX_NAME = 1 << 22;

    private static final IdRanges names = new IdRanges();
    /** Target (index of {@link #targetSlot}) of each query's last glBeginQuery, by name. */
    private static byte[] kind = new byte[16];
    /** The query currently active on each target (0 = none). */
    private static final int[] active = new int[TARGETS];

    private Queries() {
    }

    private static int targetSlot(int target) {
        switch (target) {
            case GL15.GL_SAMPLES_PASSED:
                return 0;
            case GL33.GL_ANY_SAMPLES_PASSED:
                return 1;
            case GL33.GL_TIME_ELAPSED:
                return 2;
            default:
                return -1;
        }
    }

    public static int genQuery() {
        int id = names.allocate(1);
        if (id == 0 || id > MAX_NAME) {
            if (id != 0) {
                names.free(id, 1);
            }
            GLErrors.set(GL11.GL_OUT_OF_MEMORY);
            return 0;
        }
        return id;
    }

    /** Fills {@code remaining()} names at the absolute positions (LWJGL 2 does not move the position). */
    public static void genQueries(IntBuffer ids) {
        int p = ids.position();
        for (int i = 0, n = ids.remaining(); i < n; i++) {
            ids.put(p + i, genQuery());
        }
    }

    public static void deleteQuery(int id) {
        if (id <= 0 || !names.isAllocated(id)) {
            return;
        }
        for (int t = 0; t < TARGETS; t++) {
            if (active[t] == id) {
                active[t] = 0;
            }
        }
        names.free(id, 1);
    }

    public static void deleteQueries(IntBuffer ids) {
        for (int i = ids.position(), end = ids.limit(); i < end; i++) {
            deleteQuery(ids.get(i));
        }
    }

    /** True for a name returned by glGenQueries and not deleted since. */
    public static boolean isQuery(int id) {
        return id > 0 && names.isAllocated(id);
    }

    public static void begin(int target, int id) {
        int t = targetSlot(target);
        if (t < 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (id == 0 || active[t] != 0) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (!names.isAllocated(id)) {
            // a name the application made up: like binding a buffer name, it becomes a query object
            if (id < 0 || id > MAX_NAME) {
                GLErrors.set(GL11.GL_INVALID_VALUE);
                return;
            }
            names.reserve(id);
        }
        if (id >= kind.length) {
            kind = java.util.Arrays.copyOf(kind, Math.max(id + 1, kind.length * 2));
        }
        kind[id] = (byte) t;
        active[t] = id;
    }

    public static void end(int target) {
        int t = targetSlot(target);
        if (t < 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (active[t] == 0) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        active[t] = 0;
    }

    /** glGetQuery: GL_CURRENT_QUERY or GL_QUERY_COUNTER_BITS. */
    public static int getQuery(int target, int pname) {
        int t = targetSlot(target);
        if (t < 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return 0;
        }
        switch (pname) {
            case GL15.GL_CURRENT_QUERY:
                return active[t];
            case GL15.GL_QUERY_COUNTER_BITS:
                return t == 2 ? 64 : 32;
            default:
                GLErrors.set(GL11.GL_INVALID_ENUM);
                return 0;
        }
    }

    /** glGetQueryObject*: the result is always available; occlusion queries report one visible sample, timer queries 0. */
    public static long getObject(int id, int pname) {
        if (!isQuery(id)) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return 0L;
        }
        switch (pname) {
            case GL15.GL_QUERY_RESULT_AVAILABLE:
                return 1L;
            case GL15.GL_QUERY_RESULT:
                return id < kind.length && kind[id] == 2 ? 0L : 1L;
            default:
                GLErrors.set(GL11.GL_INVALID_ENUM);
                return 0L;
        }
    }

    static void shutdown() {
        names.clear();
        java.util.Arrays.fill(active, 0);
    }
}
