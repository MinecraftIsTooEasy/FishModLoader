package net.xiaoyu233.fml.vulkan.mixin.opt;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Plugin of every {@code fishmodloader.opt.<area>.mixins.json} (non-render optimisations: net, regionio, entity, input, fps, quality,
 * reload). A mixin of an area is applied unless {@code -Dfml.vulkan.opt.<area>=false} or {@code -Dfml.vulkan.opt=false}.
 * Conflict detection (ConflictGuard) may add a reason through {@link #disable(String)} before the configs are loaded.
 */
public final class OptMixinPlugin implements IMixinConfigPlugin {
    public static final List<String> AREAS = List.of("net", "regionio", "entity", "input", "fps", "quality", "reload");
    private static final Set<String> FORCED_OFF = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private String area = "";

    public static String configName(String area) {
        return "fishmodloader.opt." + area + ".mixins.json";
    }

    /** Disables an area programmatically (a conflicting mod is present). Must run before Mixin loads the config. */
    public static void disable(String area) {
        FORCED_OFF.add(area);
    }

    /** True when the area's mixins are applied. Runtime code of an area should also gate on this. */
    public static boolean areaActive(String area) {
        net.xiaoyu233.fml.vulkan.opt.ConflictGuard.detect(); // lazy, once; mods are known when the opt configs load
        if (FORCED_OFF.contains(area) || "false".equalsIgnoreCase(System.getProperty("fml.vulkan.opt"))) {
            return false;
        }
        return !"false".equalsIgnoreCase(System.getProperty("fml.vulkan.opt." + area));
    }

    @Override
    public void onLoad(String mixinPackage) {
        String p = mixinPackage.endsWith(".") ? mixinPackage.substring(0, mixinPackage.length() - 1) : mixinPackage;
        this.area = p.substring(p.lastIndexOf('.') + 1);
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return areaActive(area); }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) { }
    @Override public void postApply(String t, ClassNode c, String m, IMixinInfo i) { }
}
