package net.xiaoyu233.fml.vulkan.mixin.opt.reload;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.Minecraft;
import net.minecraft.MetadataSerializer;
import net.minecraft.Resource;
import net.minecraft.ResourceLocation;
import net.minecraft.SimpleReloadableResourceManager;
import net.minecraft.SimpleResource;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.mixin.opt.OptMixinPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Area "reload" (WP-42): the I/O half of a resource reload. Right before the reload listeners run (still in their original order on
 * the calling thread) the language files of every domain are read on worker threads, and {@code getAllResources} answers from that
 * prefetch while the listeners run. Parsing and listener order are untouched, so the result is identical to the sequential path.
 * All logic lives in this class (a mixin package class must not be referenced from the target), hence the private statics.
 * Gate: {@code -Dfml.vulkan.opt.reload.parallel} (default true); {@code -Dfml.vulkan.opt.reload.measure=true} logs a one-off A/B.
 */
@Mixin(SimpleReloadableResourceManager.class)
public abstract class SimpleReloadableResourceManagerReloadMixin {
    @Shadow
    @Final
    private MetadataSerializer rmMetadataSerializer;

    /** Prefetched files per location (one entry per pack that has the file, in pack order). Null when no prefetch is active. */
    private volatile Map<ResourceLocation, List<byte[]>> fmlReloadPrefetch;
    private static volatile ExecutorService fmlReloadPool;
    private static volatile boolean fmlReloadMeasured;

    private static ExecutorService fmlReloadPool() {
        ExecutorService p = fmlReloadPool;
        if (p == null) {
            synchronized (SimpleReloadableResourceManagerReloadMixin.class) {
                p = fmlReloadPool;
                if (p == null) {
                    int n = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));
                    p = Executors.newFixedThreadPool(n, r -> {
                        Thread t = new Thread(r, "FML-ReloadPrefetch");
                        t.setDaemon(true);
                        return t;
                    });
                    fmlReloadPool = p;
                }
            }
        }
        return p;
    }

    private static boolean fmlReloadEnabled() {
        return OptMixinPlugin.areaActive("reload") && !"false".equalsIgnoreCase(System.getProperty("fml.vulkan.opt.reload.parallel"));
    }

    private static List<byte[]> fmlReadAll(SimpleReloadableResourceManager self, ResourceLocation loc) {
        try {
            List<?> all = self.getAllResources(loc);
            List<byte[]> out = new ArrayList<>(all.size());
            for (Object o : all) {
                Resource r = (Resource) o;
                if (r.hasMetadata()) {
                    return null; // keep the original path for resources with .mcmeta
                }
                try (InputStream in = r.getInputStream()) {
                    out.add(in.readAllBytes());
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static List<ResourceLocation> fmlLangLocations(SimpleReloadableResourceManager self) {
        List<String> langs = new ArrayList<>(Arrays.asList("en_US"));
        String cur = Minecraft.getMinecraft() != null && Minecraft.getMinecraft().gameSettings != null
            ? Minecraft.getMinecraft().gameSettings.language : null;
        if (cur != null && !"en_US".equals(cur)) {
            langs.add(cur);
        }
        langs.add("MITE");
        List<ResourceLocation> out = new ArrayList<>();
        for (String l : langs) {
            for (Object d : new ArrayList<>(self.getResourceDomains())) {
                out.add(new ResourceLocation((String) d, String.format("lang/%s.lang", l)));
            }
        }
        return out;
    }

    private static Map<ResourceLocation, List<byte[]>> fmlPrefetch(SimpleReloadableResourceManager self, boolean parallel) {
        List<ResourceLocation> locs = fmlLangLocations(self);
        Map<ResourceLocation, List<byte[]>> map = new ConcurrentHashMap<>();
        if (!parallel) {
            for (ResourceLocation l : locs) {
                List<byte[]> b = fmlReadAll(self, l);
                if (b != null) {
                    map.put(l, b);
                }
            }
            return map;
        }
        List<Future<?>> fs = new ArrayList<>();
        for (ResourceLocation l : locs) {
            fs.add(fmlReloadPool().submit(() -> {
                List<byte[]> b = fmlReadAll(self, l);
                if (b != null) {
                    map.put(l, b);
                }
            }));
        }
        for (Future<?> f : fs) {
            try {
                f.get();
            } catch (Exception e) {
                // missing entries simply fall back to the normal read
            }
        }
        return map;
    }

    @Inject(method = "notifyReloadListeners", at = @At("HEAD"), require = 0)
    private void fml$prefetch(CallbackInfo ci) {
        if (!fmlReloadEnabled()) {
            return;
        }
        SimpleReloadableResourceManager self = (SimpleReloadableResourceManager) (Object) this;
        try {
            if (!fmlReloadMeasured && "true".equalsIgnoreCase(System.getProperty("fml.vulkan.opt.reload.measure"))) {
                fmlReloadMeasured = true;
                long t0 = System.nanoTime();
                Map<ResourceLocation, List<byte[]>> seq = fmlPrefetch(self, false);
                long seqMs = (System.nanoTime() - t0) / 1_000_000L;
                t0 = System.nanoTime();
                Map<ResourceLocation, List<byte[]>> par = fmlPrefetch(self, true);
                long parMs = (System.nanoTime() - t0) / 1_000_000L;
                boolean same = seq.size() == par.size();
                for (Map.Entry<ResourceLocation, List<byte[]>> e : seq.entrySet()) {
                    List<byte[]> o = par.get(e.getKey());
                    if (o == null || o.size() != e.getValue().size()) {
                        same = false;
                        break;
                    }
                    for (int i = 0; i < o.size(); i++) {
                        same &= Arrays.equals(o.get(i), e.getValue().get(i));
                    }
                }
                VkLog.LOGGER.info("resource reload prep: {} ms (sequential {} ms), identical={}", parMs, seqMs, same);
                this.fmlReloadPrefetch = same ? par : null;
            } else {
                this.fmlReloadPrefetch = fmlPrefetch(self, true);
            }
        } catch (Throwable t) {
            this.fmlReloadPrefetch = null;
        }
    }

    @Inject(method = "notifyReloadListeners", at = @At("RETURN"), require = 0)
    private void fml$prefetchDone(CallbackInfo ci) {
        this.fmlReloadPrefetch = null;
    }

    @Inject(method = "getAllResources", at = @At("HEAD"), cancellable = true, require = 0)
    private void fml$cachedAll(ResourceLocation loc, CallbackInfoReturnable<List> cir) {
        Map<ResourceLocation, List<byte[]>> m = this.fmlReloadPrefetch;
        if (m == null) {
            return;
        }
        List<byte[]> bytes = m.get(loc);
        if (bytes == null) {
            return;
        }
        List<Object> out = new ArrayList<>(bytes.size());
        for (byte[] b : bytes) {
            out.add(new SimpleResource(loc, new ByteArrayInputStream(b), null, this.rmMetadataSerializer));
        }
        cir.setReturnValue(out);
    }
}
