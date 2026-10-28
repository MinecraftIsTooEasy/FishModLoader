package net.xiaoyu233.fml.vulkan.testbot.cmd.nr01;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;
import java.util.TreeMap;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuAllocation;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuArena;
import net.xiaoyu233.fml.vulkan.render.gpu.Tlsf;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/**
 * {@code arenaTest [operations]}: 100k random allocate / free operations (default) against the TLSF allocator alone and against a real
 * {@link GpuArena} (deferred frees, multiple pages, uploads), verifying after the run and periodically that no two live allocations
 * overlap, every offset is unit aligned and the allocator structure is consistent, and reporting the fragmentation.
 */
final class ArenaTestCommand implements DevCommand {
    @Override
    public String name() {
        return "arenaTest";
    }

    @Override
    public String description() {
        return "100k random alloc/free on Tlsf and GpuArena: no overlap, deferred frees, fragmentation report (arg: operation count)";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        int ops = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
        runTlsf(s, ops);
        if (RenderCore.isInitialized()) {
            RenderTasks.run(new ArenaTask(s, ops), 300);
        } else {
            s.warn("arenaTest GpuArena", "Vulkan backend not running: only the allocator was tested");
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Live blocks keyed by offset; checks overlap on insertion. */
    private static final class Ledger {
        final TreeMap<Long, Long> live = new TreeMap<>();   // offset -> size
        String overlap(long offset, long size) {
            var below = live.floorEntry(offset);
            if (below != null && below.getKey() + below.getValue() > offset) {
                return "overlaps the block at " + below.getKey() + "+" + below.getValue();
            }
            var above = live.ceilingEntry(offset);
            if (above != null && above.getKey() < offset + size) {
                return "overlaps the block at " + above.getKey() + "+" + above.getValue();
            }
            return null;
        }
    }

    private static void runTlsf(Report.Section s, int ops) {
        final int total = 1 << 20;
        Tlsf t = new Tlsf(total);
        Random rnd = new Random(0xA7E4AL);
        ArrayList<int[]> live = new ArrayList<>();           // {block, offset, size}
        Ledger ledger = new Ledger();
        int failures = 0;
        long allocated = 0;
        long refused = 0;
        long peakFreeBlocks = 0;
        for (int i = 0; i < ops; i++) {
            boolean doAlloc = live.isEmpty() || (rnd.nextInt(100) < 52 && live.size() < 60_000);
            if (doAlloc) {
                int units = switch (rnd.nextInt(10)) {
                    case 0 -> 1 + rnd.nextInt(8);
                    case 1, 2, 3, 4 -> 1 + rnd.nextInt(64);
                    case 5, 6, 7 -> 1 + rnd.nextInt(512);
                    case 8 -> 1 + rnd.nextInt(4096);
                    default -> 1 + rnd.nextInt(32768);
                };
                int b = t.allocate(units);
                if (b < 0) {
                    refused++;
                    if (t.largestFree() >= units) {
                        failures++;
                        s.fail("arenaTest Tlsf", "refused " + units + " units although a free block of " + t.largestFree() + " exists");
                    }
                    continue;
                }
                int off = t.offset(b);
                int size = t.size(b);
                String problem = size < units ? "block smaller than requested" : off < 0 || (long) off + size > total ? "outside the page"
                        : ledger.overlap(off, size);
                if (problem != null) {
                    failures++;
                    s.fail("arenaTest Tlsf", "allocation " + off + "+" + size + " " + problem);
                    break;
                }
                ledger.live.put((long) off, (long) size);
                live.add(new int[] {b, off, size});
                allocated++;
            } else {
                int index = rnd.nextInt(live.size());
                int[] e = live.get(index);
                live.set(index, live.get(live.size() - 1));
                live.remove(live.size() - 1);
                ledger.live.remove((long) e[1]);
                t.free(e[0]);
            }
            peakFreeBlocks = Math.max(peakFreeBlocks, t.freeBlockCount());
            if ((i & 0x3FFF) == 0) {
                String v = t.validate();
                if (v != null) {
                    failures++;
                    s.fail("arenaTest Tlsf", "structure broken after " + i + " operations: " + v);
                    break;
                }
            }
        }
        String v = t.validate();
        if (v != null) {
            failures++;
            s.fail("arenaTest Tlsf", "structure broken: " + v);
        }
        long liveUnits = 0;
        for (int[] e : live) {
            liveUnits += e[2];
        }
        if (liveUnits != t.usedUnits()) {
            failures++;
            s.fail("arenaTest Tlsf", "usedUnits " + t.usedUnits() + " != sum of live blocks " + liveUnits);
        }
        String report = "Tlsf " + total + " units, " + ops + " operations: " + allocated + " allocations, " + refused + " refused, " + live.size()
                + " live (" + liveUnits + " units), " + t.freeBlockCount() + " free blocks (peak " + peakFreeBlocks + "), largest free "
                + t.largestFree() + " units";
        for (int[] e : live) {
            t.free(e[0]);
        }
        if (t.freeBlockCount() != 1 || t.largestFree() != total || t.validate() != null) {
            failures++;
            s.fail("arenaTest Tlsf", "after freeing everything the page is not one free block: " + t.freeBlockCount() + " blocks, largest "
                    + t.largestFree());
        }
        s.check(failures == 0, "arenaTest Tlsf", "see failures above");
        s.info("arenaTest Tlsf", report + "; fully coalesced after freeing everything");
    }

    // ---------------------------------------------------------------------------------------------------------

    private static final class ArenaTask implements RenderTasks.Task {
        private static final int UNIT = 112;
        private static final long PAGE = 1L << 20;     // 1 MiB pages: ~9362 units, so the test spans several pages
        private static final int OPS_PER_FRAME = 4000;

        private final Report.Section s;
        private final int ops;
        private final Random rnd = new Random(0x5EEDL);
        private GpuArena arena;
        private final ArrayList<GpuAllocation> live = new ArrayList<>();
        private final Ledger[] pageLedgers = new Ledger[64];
        private int done;
        private int refused;
        private int maxPages;
        private int failures;
        private long uploads;
        private ByteBuffer data;
        private long startFrame = -1;

        ArenaTask(Report.Section s, int ops) {
            this.s = s;
            this.ops = ops;
        }

        @Override
        public boolean step(long frame) {
            if (arena == null) {
                arena = new GpuArena("arenaTest", VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, PAGE, UNIT);
                data = MemoryUtil.memAlloc(UNIT * 64);
                for (int i = 0; i < data.capacity(); i++) {
                    data.put(i, (byte) i);
                }
                startFrame = frame;
            }
            int budget = Math.min(OPS_PER_FRAME, ops - done);
            for (int i = 0; i < budget && failures == 0; i++) {
                if (live.isEmpty() || (rnd.nextInt(100) < 55 && live.size() < 40_000)) {
                    int bytes = 1 + rnd.nextInt(switch (rnd.nextInt(4)) {
                        case 0 -> UNIT;
                        case 1 -> UNIT * 8;
                        case 2 -> UNIT * 64;
                        default -> UNIT * 1024;
                    });
                    GpuAllocation a = arena.allocate(bytes);
                    if (a == null) {
                        refused++;
                        continue;
                    }
                    check(a, bytes);
                    if (a.page < pageLedgers.length) {
                        Ledger l = pageLedgers[a.page];
                        if (l == null) {
                            l = pageLedgers[a.page] = new Ledger();
                        }
                        String problem = l.overlap(a.offset, a.size);
                        if (problem != null) {
                            fail("allocation " + a + " " + problem);
                        }
                        l.live.put(a.offset, a.size);
                    }
                    live.add(a);
                    if (rnd.nextInt(8) == 0) {
                        data.clear().limit((int) Math.min(a.size, data.capacity()));
                        arena.upload(a, data);
                        uploads++;
                    }
                } else {
                    int index = rnd.nextInt(live.size());
                    GpuAllocation a = live.get(index);
                    live.set(index, live.get(live.size() - 1));
                    live.remove(live.size() - 1);
                    if (a.page < pageLedgers.length) {
                        pageLedgers[a.page].live.remove(a.offset);
                    }
                    arena.free(a);
                }
                maxPages = Math.max(maxPages, arena.pageCount());
            }
            done += budget;
            if (failures == 0 && (done % 20_000) < OPS_PER_FRAME) {
                String v = arena.validate();
                if (v != null) {
                    fail("allocator structure: " + v);
                }
            }
            if (failures > 0 && drainStart < 0) {
                finish(false);
                return false;
            }
            if (done < ops && drainStart < 0) {
                return false;
            }
            return finish(failures == 0);
        }

        private void check(GpuAllocation a, int bytes) {
            if (a.size < bytes || a.size % UNIT != 0 || a.offset % UNIT != 0 || a.offset < 0 || a.offset + a.size > PAGE) {
                fail("bad allocation " + a + " for " + bytes + " bytes");
            }
        }

        private void fail(String what) {
            failures++;
            s.fail("arenaTest GpuArena", what);
        }

        private long drainStart = -1;
        private String finishReport;
        private long usedBefore;

        private boolean finish(boolean completed) {
            if (drainStart < 0) {
                finishReport = arena.fragmentationReport();
                usedBefore = arena.bytesUsed();
                for (GpuAllocation a : live) {
                    arena.free(a);
                }
                live.clear();
                s.info("arenaTest GpuArena", completed ? done + " operations, " + refused + " refused, " + uploads + " uploads, up to "
                        + maxPages + " pages; " + finishReport.replace("\n", " | ") : "aborted");
                s.check(failures == 0, "arenaTest GpuArena", "see failures above");
                drainStart = RenderCore.frameNumber();
                if (arena.bytesUsed() != 0) {
                    fail("bytesUsed " + arena.bytesUsed() + " after freeing every allocation");
                }
                return false;
            }
            // frees retire framesInFlight frames after they were made: wait for that and check the allocator is whole again
            long waited = RenderCore.frameNumber() - drainStart;
            if (waited <= RenderCore.framesInFlight() + 1 && arena.bytesPendingFree() != 0) {
                return false;
            }
            s.check(arena.bytesPendingFree() == 0, "arenaTest deferred frees retire within framesInFlight+1 frames",
                    arena.bytesPendingFree() + " bytes still pending after " + waited + " frames");
            String v = arena.validate();
            s.check(v == null, "arenaTest allocator consistent after the drain", String.valueOf(v));
            s.info("arenaTest after drain", arena.fragmentationReport().replace("\n", " | "));
            MemoryUtil.memFree(data);
            data = null;
            arena.destroy();
            s.check(arena.pageCount() == 0, "arenaTest destroy releases all pages", "pages left: " + arena.pageCount());
            return true;
        }
    }
}
