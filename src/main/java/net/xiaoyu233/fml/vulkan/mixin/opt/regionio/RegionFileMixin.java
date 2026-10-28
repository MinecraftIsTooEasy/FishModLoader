package net.xiaoyu233.fml.vulkan.mixin.opt.regionio;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.RegionFile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WP-39 chunk read-ahead for region files. After a chunk read, the compressed payloads of the neighbouring chunks of the same region are
 * read on a private daemon thread (separate read-only channel, never the shared RandomAccessFile pointer) into a bounded per-region LRU.
 * Any write or close bumps a version and clears the cache; a prefetch only stores its result when the version is unchanged, so a read
 * never returns stale data. On-disk format is untouched. Gate: area 'regionio' and -Dfml.vulkan.opt.regionio.readahead (default true).
 */
@Mixin(RegionFile.class)
public abstract class RegionFileMixin {
    @Shadow @Final private File fileName;
    @Shadow @Final private int[] offsets;
    @Shadow private java.util.ArrayList sectorFree;

    @Unique private LinkedHashMap<Integer, byte[]> fml$cacheMap;
    @Unique private int fml$version;
    @Unique private boolean fml$closed;
    @Unique private FileChannel fml$channel;

    /** Lazy: the initialiser of a mixin field is not reliably merged into the constructor of this target (RegionFile(File) here), the field stayed null. */
    @Unique
    private synchronized LinkedHashMap<Integer, byte[]> fml$cache() {
        LinkedHashMap<Integer, byte[]> m = this.fml$cacheMap;
        if (m == null) {
            m = this.fml$cacheMap = new LinkedHashMap<>(16, 0.75f, true);
        }
        return m;
    }

    @Unique private static final int FML$MAX_ENTRIES = 64;
    @Unique private static final AtomicLong FML$READS = new AtomicLong();
    @Unique private static final AtomicLong FML$HITS = new AtomicLong();
    @Unique private static final AtomicLong FML$BYTES = new AtomicLong();
    @Unique private static final AtomicLong FML$PREFETCHED = new AtomicLong();
    @Unique private static volatile ThreadPoolExecutor fml$pool;
    @Unique private static boolean fml$hook;
    @Unique private static final boolean FML$READAHEAD = !"false".equalsIgnoreCase(System.getProperty("fml.vulkan.opt.regionio.readahead"));

    @Unique
    private static synchronized ThreadPoolExecutor fml$pool() {
        if (fml$pool == null) {
            ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "FML-RegionReadAhead");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.DiscardOldestPolicy());
            p.allowCoreThreadTimeOut(true);
            fml$pool = p;
        }
        if (!fml$hook) {
            fml$hook = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("[FML/regionio] reads=" + FML$READS.get()
                + " cacheHits=" + FML$HITS.get() + " prefetched=" + FML$PREFETCHED.get() + " bytes=" + FML$BYTES.get()), "FML-RegionIoStats"));
        }
        return fml$pool;
    }

    @Inject(method = "getChunkDataInputStream", at = @At("HEAD"), cancellable = true)
    private void fml$readAhead(int x, int z, CallbackInfoReturnable<DataInputStream> cir) {
        if (x < 0 || x >= 32 || z < 0 || z >= 32) {
            return;
        }
        FML$READS.incrementAndGet();
        if (!FML$READAHEAD) {
            return;
        }
        int idx = x + z * 32;
        byte[] data;
        synchronized (this) {
            data = this.fml$cache().remove(idx);
        }
        if (data != null) {
            try {
                DataInputStream in = new DataInputStream(new BufferedInputStream(data[0] == 1
                    ? new GZIPInputStream(new ByteArrayInputStream(data, 1, data.length - 1))
                    : new InflaterInputStream(new ByteArrayInputStream(data, 1, data.length - 1))));
                FML$HITS.incrementAndGet();
                FML$BYTES.addAndGet(data.length);
                cir.setReturnValue(in);
            } catch (IOException e) {
                cir.setReturnValue(null);
            }
        }
        // schedule neighbours (cheap check of the offset table first)
        boolean any = false;
        for (int dz = -1; dz <= 1 && !any; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int nz = z + dz;
                if ((dx != 0 || dz != 0) && nx >= 0 && nx < 32 && nz >= 0 && nz < 32 && this.offsets[nx + nz * 32] != 0) {
                    any = true;
                    break;
                }
            }
        }
        if (any) {
            try {
                fml$pool().execute(() -> this.fml$prefetch(x, z));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Unique
    private void fml$prefetch(int x, int z) {
        int version;
        FileChannel ch;
        List<int[]> todo = new ArrayList<>(8);
        synchronized (this) {
            if (this.fml$closed) {
                return;
            }
            version = this.fml$version;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx;
                    int nz = z + dz;
                    if ((dx == 0 && dz == 0) || nx < 0 || nx >= 32 || nz < 0 || nz >= 32) {
                        continue;
                    }
                    int idx = nx + nz * 32;
                    int off = this.offsets[idx];
                    if (off != 0 && !this.fml$cache().containsKey(idx) && (off >> 8) + (off & 0xFF) <= this.sectorFree.size()) {
                        todo.add(new int[] {idx, off});
                    }
                }
            }
            if (todo.isEmpty()) {
                return;
            }
            try {
                if (this.fml$channel == null) {
                    this.fml$channel = FileChannel.open(this.fileName.toPath(), StandardOpenOption.READ);
                }
            } catch (IOException e) {
                return;
            }
            ch = this.fml$channel;
        }
        for (int[] t : todo) {
            try {
                long pos = (long) (t[1] >> 8) * 4096L;
                ByteBuffer head = ByteBuffer.allocate(5);
                if (!fml$readFully(ch, head, pos)) {
                    continue;
                }
                head.flip();
                int len = head.getInt();
                byte type = head.get();
                if (len <= 1 || len > 4096 * (t[1] & 0xFF) || (type != 1 && type != 2)) {
                    continue;
                }
                byte[] buf = new byte[len];
                buf[0] = type;
                if (!fml$readFully(ch, ByteBuffer.wrap(buf, 1, len - 1), pos + 5)) {
                    continue;
                }
                synchronized (this) {
                    if (this.fml$closed || this.fml$version != version) {
                        return;
                    }
                    this.fml$cache().put(t[0], buf);
                    FML$PREFETCHED.incrementAndGet();
                    while (this.fml$cache().size() > FML$MAX_ENTRIES) {
                        Integer eldest = this.fml$cache().keySet().iterator().next();
                        this.fml$cache().remove(eldest);
                    }
                }
            } catch (IOException | RuntimeException e) {
                return;
            }
        }
    }

    @Unique
    private static boolean fml$readFully(FileChannel ch, ByteBuffer bb, long pos) throws IOException {
        int start = bb.position();
        while (bb.hasRemaining()) {
            int n = ch.read(bb, pos + bb.position() - start);
            if (n < 0) {
                return false;
            }
        }
        return true;
    }

    @Inject(method = "write(II[BI)V", at = @At("HEAD"))
    private void fml$invalidateOnWrite(int x, int z, byte[] data, int len, CallbackInfo ci) {
        this.fml$version++;
        this.fml$cache().clear();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void fml$onClose(CallbackInfo ci) {
        synchronized (this) {
            this.fml$closed = true;
            this.fml$version++;
            this.fml$cache().clear();
            if (this.fml$channel != null) {
                try {
                    this.fml$channel.close();
                } catch (IOException ignored) {
                }
                this.fml$channel = null;
            }
        }
    }
}
