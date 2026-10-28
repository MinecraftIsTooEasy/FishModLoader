package vkbench;

import java.util.Arrays;
import java.util.Locale;

/** Frame time statistics of one segment: average FPS, 1 % / 0.1 % low, variance and percentiles of frame_ms (7.3 metrics), plus the memory and GC maxima. */
final class BenchStats {
    final String segment;
    float[] ms = new float[1 << 14];
    int n;
    double sumMs;
    long heapMaxMb = -1;
    long rssMaxMb = -1;
    long vmaMaxMb = -1;
    long gcCount0 = -1;
    long gcMs0 = -1;
    long gcCount1;
    long gcMs1;
    long chunkUpdates;
    long drawsSum;
    long drawsFrames;
    double gpuMsSum;
    long gpuFrames;
    double settleSeconds = -1;

    BenchStats(String segment) {
        this.segment = segment;
    }

    void add(float frameMs, long draws, double gpu, long heapMb, long rssMb, long vmaMb, long gcCount, long gcMillis) {
        if (n == ms.length) {
            ms = Arrays.copyOf(ms, n * 2);
        }
        ms[n++] = frameMs;
        sumMs += frameMs;
        if (draws >= 0) {
            drawsSum += draws;
            drawsFrames++;
        }
        if (gpu >= 0) {
            gpuMsSum += gpu;
            gpuFrames++;
        }
        heapMaxMb = Math.max(heapMaxMb, heapMb);
        rssMaxMb = Math.max(rssMaxMb, rssMb);
        vmaMaxMb = Math.max(vmaMaxMb, vmaMb);
        if (gcCount0 < 0) {
            gcCount0 = gcCount;
            gcMs0 = gcMillis;
        }
        gcCount1 = gcCount;
        gcMs1 = gcMillis;
    }

    /** Percentile {@code q} (0..1) of the frame times, nearest rank on the sorted copy. */
    double percentile(float[] sorted, double q) {
        if (n == 0) {
            return Double.NaN;
        }
        int idx = (int) Math.ceil(q * n) - 1;
        return sorted[Math.max(0, Math.min(n - 1, idx))];
    }

    /** All derived numbers; the sort happens once here (end of a segment, not in the frame loop). */
    Summary summarise() {
        float[] sorted = Arrays.copyOf(ms, n);
        Arrays.sort(sorted);
        double mean = n == 0 ? Double.NaN : sumMs / n;
        double var = 0;
        for (int i = 0; i < n; i++) {
            double d = ms[i] - mean;
            var += d * d;
        }
        var = n < 2 ? 0 : var / (n - 1);
        double p99 = percentile(sorted, 0.99);
        double p999 = percentile(sorted, 0.999);
        return new Summary(segment, n, n == 0 ? Double.NaN : 1000.0 / mean, 1000.0 / p99, 1000.0 / p999, mean, var, Math.sqrt(var), percentile(sorted, 0.5), percentile(sorted, 0.95), p99,
            n == 0 ? Double.NaN : sorted[n - 1], heapMaxMb, rssMaxMb, vmaMaxMb, Math.max(0, gcCount1 - Math.max(0, gcCount0)), Math.max(0, gcMs1 - Math.max(0, gcMs0)), chunkUpdates,
            drawsFrames == 0 ? -1 : (double) drawsSum / drawsFrames, gpuFrames == 0 ? -1 : gpuMsSum / gpuFrames, settleSeconds);
    }

    record Summary(String segment, int frames, double avgFps, double low1, double low01, double meanMs, double varianceMs2, double stddevMs, double p50, double p95, double p99, double maxMs,
                   long heapMaxMb, long rssMaxMb, long vmaMaxMb, long gcCount, long gcMs, long chunkUpdates, double drawsPerFrame, double gpuMsAvg, double settleSeconds) {
        static String header() {
            return "segment,frames,avg_fps,low1_fps,low01_fps,mean_ms,variance_ms2,stddev_ms,p50_ms,p95_ms,p99_ms,max_ms,heap_max_mb,rss_max_mb,vma_max_mb,gc_count,gc_ms,chunk_updates,draws_per_frame,gpu_ms_avg,settle_s";
        }

        String csv() {
            return String.format(Locale.ROOT, "%s,%d,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%.1f,%.3f,%.2f", segment, frames, avgFps, low1, low01, meanMs, varianceMs2,
                stddevMs, p50, p95, p99, maxMs, heapMaxMb, rssMaxMb, vmaMaxMb, gcCount, gcMs, chunkUpdates, drawsPerFrame, gpuMsAvg, settleSeconds);
        }
    }
}
