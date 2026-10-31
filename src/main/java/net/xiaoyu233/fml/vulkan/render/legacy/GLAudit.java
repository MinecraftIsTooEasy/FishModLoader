package net.xiaoyu233.fml.vulkan.render.legacy;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Audit counters of the GL safety net (1.16.5, 5.11).
 * <p>
 * Thread-safety: the GL facade is assumed to be called from the render thread. Per-function call counters are plain
 * {@code long[]} slots with racy increments (lost updates under contention are acceptable for an audit); the arrays are
 * published through volatile references and grown on registration. Stack walking happens only per the sampling rule
 * (FIRST: count &lt;= 64 or count % 4096 == 0; FULL: every call; OFF: never) and is guarded against reentrancy.
 */
public final class GLAudit {
    private static final int INITIAL = 8192;
    private static final String[] SKIP_PREFIXES = {
            "org.lwjgl.", "net.xiaoyu233.fml.vulkan.gl.", "net.xiaoyu233.fml.vulkan.render.legacy.",
            "java.", "jdk.internal."};
    private static final StackWalker WALKER = StackWalker.getInstance();

    private static final ConcurrentHashMap<String, Integer> IDS = new ConcurrentHashMap<>();
    private static final Object REG_LOCK = new Object();
    private static volatile String[] names = new String[INITIAL];
    private static volatile long[] calls = new long[INITIAL];
    private static volatile long[] gameCalls = new long[INITIAL];
    private static volatile long[] modCalls = new long[INITIAL];
    private static int nextId;

    private static final ConcurrentHashMap<String, Boolean> GAME_SITES = new ConcurrentHashMap<>();
    private static final AtomicLong GAME_SITE_COUNT = new AtomicLong();
    private static final AtomicLong MOD_SITE_COUNT = new AtomicLong();
    private static volatile GLAuditMode mode = GLAuditMode.parse(System.getProperty("fml.vulkan.glAudit"));
    private static final ThreadLocal<boolean[]> BUSY = ThreadLocal.withInitial(() -> new boolean[1]);

    private GLAudit() {
    }

    /** Registers a facade function (class.name) and returns its stable id; used by generated facades via a static table. */
    public static int register(String qualifiedName) {
        Integer id = IDS.get(qualifiedName);
        if (id != null) {
            return id;
        }
        synchronized (REG_LOCK) {
            id = IDS.get(qualifiedName);
            if (id != null) {
                return id;
            }
            int n = nextId++;
            if (n >= names.length) {
                int cap = names.length * 2;
                names = java.util.Arrays.copyOf(names, cap);
                calls = java.util.Arrays.copyOf(calls, cap);
                gameCalls = java.util.Arrays.copyOf(gameCalls, cap);
                modCalls = java.util.Arrays.copyOf(modCalls, cap);
            }
            names[n] = qualifiedName;
            IDS.put(qualifiedName, n);
            return n;
        }
    }

    public static GLAuditMode mode() {
        return mode;
    }

    /** Distinct unmigrated game call sites seen so far ("class.method:line GLfunction"). */
    public static List<String> gameSites() {
        List<String> l = new ArrayList<>(GAME_SITES.keySet());
        java.util.Collections.sort(l);
        return l;
    }

    public static void hit(int functionId) {
        long[] c = calls;
        if (functionId < 0 || functionId >= c.length) {
            return;
        }
        long n = ++c[functionId];
        GLAuditMode m = mode;
        if (m == GLAuditMode.OFF) {
            return;
        }
        if (m == GLAuditMode.FIRST && n > 64 && (n & 4095) != 0) {
            return;
        }
        walk(functionId);
    }

    private static void walk(int id) {
        boolean[] busy = BUSY.get();
        if (busy[0]) {
            return;
        }
        busy[0] = true;
        try {
            StackWalker.StackFrame caller = WALKER.walk(s -> s
                    .filter(f -> !skip(f.getClassName()))
                    .findFirst().orElse(null));
            if (caller == null) {
                return;
            }
            String cn = caller.getClassName();
            if (cn.startsWith("net.minecraft.")) {
                GAME_SITE_COUNT.incrementAndGet();
                gameCalls[id]++;
                String site = cn + "." + caller.getMethodName() + ":" + caller.getLineNumber() + " " + names[id];
                if (GAME_SITES.putIfAbsent(site, Boolean.TRUE) == null) {
                    VkLog.LOGGER.warn("unmigrated game GL site {}", site);
                }
            } else {
                MOD_SITE_COUNT.incrementAndGet();
                modCalls[id]++;
            }
        } catch (Throwable t) {
            // audit must never break rendering
        } finally {
            busy[0] = false;
        }
    }

    private static boolean skip(String cn) {
        for (String p : SKIP_PREFIXES) {
            if (cn.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public static long gameSiteCount() {
        return GAME_SITE_COUNT.get();
    }

    public static long modSiteCount() {
        return MOD_SITE_COUNT.get();
    }

    public static long shimFallbacks() {
        return GlShim.fallbackCount();
    }

    /** Clears all counters and sites (keeps registrations) and re-reads fml.vulkan.glAudit; for the testbot. */
    public static void reset() {
        java.util.Arrays.fill(calls, 0L);
        java.util.Arrays.fill(gameCalls, 0L);
        java.util.Arrays.fill(modCalls, 0L);
        GAME_SITES.clear();
        GAME_SITE_COUNT.set(0);
        MOD_SITE_COUNT.set(0);
        mode = GLAuditMode.parse(System.getProperty("fml.vulkan.glAudit"));
    }

    /** Writes the markdown report: function table sorted by calls, then the game site list. */
    public static void writeReport(Path markdown) {
        int n;
        synchronized (REG_LOCK) {
            n = nextId;
        }
        long[] c = calls, g = gameCalls, md = modCalls;
        String[] nm = names;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort((a, b) -> Long.compare(c[b], c[a]));
        StringBuilder sb = new StringBuilder();
        sb.append("# GL audit\n\nmode: ").append(mode).append("\n\n");
        sb.append("game sites: ").append(gameSiteCount()).append(", mod sites: ").append(modSiteCount())
                .append(", shim fallbacks: ").append(shimFallbacks()).append("\n\n");
        sb.append("| function | calls | game sites | mod sites |\n|---|---:|---:|---:|\n");
        for (int i : order) {
            sb.append("| ").append(nm[i]).append(" | ").append(c[i]).append(" | ").append(g[i]).append(" | ")
                    .append(md[i]).append(" |\n");
        }
        sb.append("\n## Unmigrated game sites\n\n");
        List<String> sites = gameSites();
        if (sites.isEmpty()) {
            sb.append("none\n");
        }
        for (String s : sites) {
            sb.append("- ").append(s).append('\n');
        }
        try {
            Path parent = markdown.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(markdown, sb.toString());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static String functionName(int id) {
        String[] nm = names;
        return id >= 0 && id < nm.length && nm[id] != null ? nm[id] : "gl#" + id;
    }
}
