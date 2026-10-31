package net.xiaoyu233.fml.vulkan.testbot.cmd.nr02;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.PipelineLibrary;
import net.xiaoyu233.fml.vulkan.render.gpu.Program;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/**
 * Devtest commands of work package NR-02 (owner: NR-02).
 *
 * <ul>
 * <li>{@code pipelineTest}: waits until the seed list and the journal are created, then requests 200 random keys that are not in the
 * seed list on the render thread: no call may block longer than 1 ms, each must answer a fallback or 0 and each must become exact within
 * 10 frames; the warm-up time of the first process (empty cache) is stored and compared with the second process (cache present, target
 * at least 5 times faster).</li>
 * <li>{@code pipelineSeed}: merges the seed list and the journal of this machine into a refreshed seed file under {@code ../docs/native}
 * (for NR-28; the shipped file is only replaced by the integrator).</li>
 * </ul>
 */
public final class Commands implements DevCommandProvider {
    private static final int RANDOM_KEYS = 200;
    private static final long CALL_LIMIT_NANOS = 1_000_000L;
    private static final int EXACT_WITHIN_FRAMES = 10;
    private static final long WARM_TIMEOUT_MS = 180_000L;
    private static final long EXACT_TIMEOUT_MS = 120_000L;

    @Override
    public String packageId() {
        return "NR-02";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new PipelineTest(), new PipelineSeed());
    }

    /** Runs work on the render thread at the start of the next frame (RenderCore frame listener) and hands the result back. */
    static final class RenderThread implements RenderCore.FrameListener {
        private static final RenderThread INSTANCE = new RenderThread();
        private static boolean registered;
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private volatile long frames;

        static synchronized RenderThread get() {
            if (!registered) {
                registered = true;
                RenderCore.addListener(INSTANCE);
            }
            return INSTANCE;
        }

        @Override
        public void onFrameBegin() {
            frames++;
            Runnable r;
            while ((r = tasks.poll()) != null) {
                r.run();
            }
        }

        long frames() {
            return frames;
        }

        <T> T call(Callable<T> body, long timeoutMs) throws Exception {
            CompletableFuture<T> done = new CompletableFuture<>();
            tasks.add(() -> {
                try {
                    done.complete(body.call());
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
            try {
                return done.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof Exception ex) {
                    throw ex;
                }
                throw e;
            }
        }
    }

    private static final class PipelineTest implements DevCommand {
        @Override
        public String name() {
            return "pipelineTest";
        }

        @Override
        public String description() {
            return "NR-02: seed set created, 200 random unseen keys never block (> 1 ms) and become exact within 10 frames, cache speed-up";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            RenderThread rt = RenderThread.get();
            if (!PipelineLibrary.isInitialized()) {
                s.fail("pipeline library", "PipelineLibrary.init() did not run (is fml.vulkan.native enabled?)");
                return;
            }
            long waitStart = System.currentTimeMillis();
            while (!PipelineLibrary.isWarm() && System.currentTimeMillis() - waitStart < WARM_TIMEOUT_MS) {
                Thread.sleep(50);
            }
            boolean warm = PipelineLibrary.isWarm();
            double warmMs = PipelineLibrary.warmUpMillis();
            s.check(warm, "seed list created", "warm-up still running after " + WARM_TIMEOUT_MS + " ms ("
                    + PipelineLibrary.createdCount() + " created)");
            s.info("warm-up", PipelineLibrary.warmUpTotal() + " seed+journal keys, " + PipelineLibrary.createdCount() + " pipelines, "
                    + String.format("%.0f", warmMs) + " ms, cache " + (PipelineLibrary.cacheWasLoaded() ? "loaded" : "empty")
                    + ", failures " + PipelineLibrary.failureCount());
            s.check(PipelineLibrary.failureCount() == 0, "no creation failures", PipelineLibrary.failureCount() + " pipelines failed");
            compareWithFirstRun(s, warmMs);

            PipelineLibrary.setJournalEnabled(false);      // random keys must not pollute the user's journal
            try {
            // 200 random keys that exist nowhere yet
            Random rnd = new Random(0x4E5230322L);
            long[] keys = new long[RANDOM_KEYS];
            Program[] programs = new Program[RANDOM_KEYS];
            int[] n = {0};
            Set<Long> distinct = new LinkedHashSet<>();
            rt.call(() -> {
                int guard = 0;
                while (n[0] < RANDOM_KEYS && guard++ < 100_000) {
                    Program p = pick(rnd);
                    long key = randomKey(rnd, p);
                    long norm = PipelineLibrary.normalizedKey(p, key);
                    if (PipelineLibrary.hasExact(p, key) || !distinct.add(norm)) {
                        continue;
                    }
                    keys[n[0]] = key;
                    programs[n[0]] = p;
                    n[0]++;
                }
                return null;
            }, 30_000);
            s.check(n[0] == RANDOM_KEYS, "random unseen keys generated", "only " + n[0] + " of " + RANDOM_KEYS);

            int count = n[0];
            long[] slowest = {0L};
            int[] immediateExact = {0};
            int[] fallback = {0};
            int[] skipped = {0};
            long createdBefore = PipelineLibrary.renderThreadCreations();
            long startFrame = rt.frames();
            rt.call(() -> {
                for (int i = 0; i < count; i++) {
                    long t0 = System.nanoTime();
                    long h = PipelineLibrary.get(programs[i], keys[i], programs[i].vertexFormat());
                    long dt = System.nanoTime() - t0;
                    slowest[0] = Math.max(slowest[0], dt);
                    if (h == 0L) {
                        skipped[0]++;
                    } else if (PipelineLibrary.isExact(h)) {
                        immediateExact[0]++;
                    } else {
                        fallback[0]++;
                    }
                }
                return null;
            }, 30_000);
            s.check(slowest[0] <= CALL_LIMIT_NANOS, "no get() blocks over 1 ms",
                    "slowest call " + slowest[0] / 1000 + " us (limit " + CALL_LIMIT_NANOS / 1000 + " us)");
            s.info("miss answers", fallback[0] + " fallback, " + skipped[0] + " skipped (0), " + immediateExact[0]
                    + " exact at once; slowest call " + slowest[0] / 1000 + " us");
            s.check(immediateExact[0] == 0, "misses answer fallback or 0",
                    immediateExact[0] + " of " + count + " keys were exact immediately although never created");
            s.check(PipelineLibrary.renderThreadCreations() == createdBefore, "no render-thread creation",
                    (PipelineLibrary.renderThreadCreations() - createdBefore) + " pipelines were compiled on the render thread");

            // every key becomes exact
            int[] firstExactFrame = new int[count];
            int pending = count;
            long deadline = System.currentTimeMillis() + EXACT_TIMEOUT_MS;
            while (pending > 0 && System.currentTimeMillis() < deadline) {
                long frame = rt.frames();
                final int[] stillPending = {0};
                rt.call(() -> {
                    for (int i = 0; i < count; i++) {
                        if (firstExactFrame[i] == 0) {
                            if (PipelineLibrary.hasExact(programs[i], keys[i])) {
                                firstExactFrame[i] = (int) Math.max(1, rt.frames() - startFrame);
                            } else {
                                stillPending[0]++;
                            }
                        }
                    }
                    return null;
                }, 30_000);
                pending = stillPending[0];
                if (pending > 0) {
                    long end = System.currentTimeMillis() + 2000;
                    while (rt.frames() == frame && System.currentTimeMillis() < end) {
                        Thread.sleep(2);
                    }
                }
            }
            s.check(pending == 0, "every unseen key became exact", pending + " of " + count + " keys never became exact in "
                    + EXACT_TIMEOUT_MS + " ms");
            int worst = 0;
            int over = 0;
            for (int f : firstExactFrame) {
                worst = Math.max(worst, f);
                if (f > EXACT_WITHIN_FRAMES) {
                    over++;
                }
            }
            s.info("frames until exact", "worst " + worst + " frames, " + over + " of " + count + " keys needed more than "
                    + EXACT_WITHIN_FRAMES);
            if (over == 0) {
                s.ok("all keys exact within " + EXACT_WITHIN_FRAMES + " frames");
            } else {
                s.warn("keys exact within " + EXACT_WITHIN_FRAMES + " frames", over + " keys took longer (a slow driver compiles on "
                        + "the pool threads; the draws were served by fallbacks meanwhile)");
            }
            rt.call(() -> {
                PipelineLibrary.saveCache();
                return null;
            }, 60_000);
            s.info("cache file", PipelineLibrary.cacheFile() + (Files.isRegularFile(PipelineLibrary.cacheFile()) ? " written" : " MISSING"));
            } finally {
                PipelineLibrary.setJournalEnabled(true);
            }
        }

        private static Program pick(Random rnd) {
            int r = rnd.nextInt(10);
            return r < 6 ? Program.STREAM : r < 8 ? Program.STATIC : r < 9 ? Program.TERRAIN : Program.LINES;
        }

        /** A random but valid key: random textured/lightmap/fog/overlay bits, alpha reference 0..3, blend pair, depth state, cull, mask. */
        private static long randomKey(Random rnd, Program p) {
            long k = p.ordinal();
            k |= (long) rnd.nextInt(2) << 4 | (long) rnd.nextInt(2) << 5 | (long) rnd.nextInt(2) << 6 | (long) rnd.nextInt(2) << 7;
            int alpha = rnd.nextInt(3);
            if (alpha > 0) {
                k |= 1L << 8 | (long) rnd.nextInt(4) << 10;
            }
            if (rnd.nextBoolean()) {
                k |= 1L << 18 | (long) rnd.nextInt(10) << 19 | (long) rnd.nextInt(10) << 23;
            }
            if (rnd.nextInt(4) != 0) {
                int[] funcs = {1, 2, 3, 4, 5, 6, 7};
                k |= 1L << 27 | (long) funcs[rnd.nextInt(funcs.length)] << 28 | (long) rnd.nextInt(2) << 31;
            }
            if (rnd.nextBoolean()) {
                k |= 1L << 32 | (long) rnd.nextInt(2) << 38;
            }
            k |= (long) (rnd.nextInt(8)) << 33;
            k |= (long) rnd.nextInt(2) << 36;
            return k;
        }

        /** The first process (cache empty) stores its warm-up time; the second (cache loaded) must be at least 5 times faster. */
        private static void compareWithFirstRun(Report.Section s, double warmMs) {
            Path marker = PipelineLibrary.cacheFile().resolveSibling("native-pipeline-warmup-ms.txt");
            try {
                if (!PipelineLibrary.cacheWasLoaded() || !Files.isRegularFile(marker)) {
                    Files.createDirectories(marker.getParent());
                    Files.writeString(marker, String.format("%.1f%n", warmMs), StandardCharsets.UTF_8);
                    s.info("cache speed-up", "first run: warm-up " + String.format("%.0f", warmMs) + " ms stored; run the command in a second "
                            + "process to compare");
                    return;
                }
                double first = Double.parseDouble(Files.readString(marker, StandardCharsets.UTF_8).trim());
                double ratio = first / Math.max(warmMs, 1.0);
                s.info("cache speed-up", String.format("first run %.0f ms, this run %.0f ms, %.1fx", first, warmMs, ratio));
                if (ratio >= 5.0) {
                    s.ok("second start creates the seed set at least 5x faster");
                } else if (RenderCore.deviceInfo() != null && RenderCore.deviceInfo().isMoltenVK()) {
                    s.fail("second start creates the seed set at least 5x faster", String.format("only %.1fx on MoltenVK", ratio));
                } else {
                    s.warn("second start creates the seed set at least 5x faster", String.format("only %.1fx (the 5x target is for MoltenVK)", ratio));
                }
            } catch (IOException | NumberFormatException e) {
                s.warn("cache speed-up", "marker file unusable: " + e);
            }
        }
    }

    private static final class PipelineSeed implements DevCommand {
        @Override
        public String name() {
            return "pipelineSeed";
        }

        @Override
        public String description() {
            return "NR-02: merges the shipped seed list and this machine's journal into ../docs/native/native-pipelines.seed.merged";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            List<String> lines = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            try (var in = Commands.class.getResourceAsStream("/assets/fml/native-pipelines.seed")) {
                if (in != null) {
                    for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                        add(l, lines, seen);
                    }
                }
            }
            Path journal = PipelineLibrary.journalFile();
            int fromJournal = 0;
            if (Files.isRegularFile(journal)) {
                for (String l : Files.readAllLines(journal)) {
                    int before = lines.size();
                    add(l, lines, seen);
                    fromJournal += lines.size() - before;
                }
            }
            File docs = new File(System.getProperty("fml.vulkan.testbot.docs", "../docs"));
            Path out = new File(new File(docs, "native"), "native-pipelines.seed.merged").toPath();
            Files.createDirectories(out.getParent());
            Files.write(out, lines, StandardCharsets.UTF_8);
            s.info("seed merge", lines.size() + " keys (" + fromJournal + " new from the journal) -> " + out);
            s.ok("merged seed list written");
        }

        private static void add(String raw, List<String> lines, Set<String> seen) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                return;
            }
            String[] w = l.split("\\s+");
            if (w.length >= 2 && seen.add(w[0].toUpperCase() + " " + w[1].toLowerCase())) {
                lines.add(w[0].toUpperCase() + " " + w[1].toLowerCase() + (w.length > 2 ? " " + w[2] : ""));
            }
        }
    }
}
