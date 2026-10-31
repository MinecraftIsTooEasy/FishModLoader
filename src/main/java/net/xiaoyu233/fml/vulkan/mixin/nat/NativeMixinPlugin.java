package net.xiaoyu233.fml.vulkan.mixin.nat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Plugin of every {@code fishmodloader.native.<area>.mixins.json} config (ARCHITECTURE-NATIVE 1.17, 5.12).
 *
 * <ul>
 * <li>{@link #shouldApplyMixin}: false for every native mixin when {@code fml.vulkan.native=false}, and for the mixins of a config whose
 * area is listed in {@code fml.vulkan.native.off} (the infrastructure areas {@link #INFRASTRUCTURE_AREAS} ignore that list).</li>
 * <li>{@link #postApply}: records (config, mixin, target) for the audit; {@link #writeAudit(Path)} compares them with the mixin lists of
 * the native configs (every listed mixin of an enabled area must have been applied to its target, otherwise the audit says FAIL).
 * {@code fml.vulkan.native.audit=true} writes the file at JVM exit, and the testbot / NR-28 may call {@link #writeAuditIfEnabled(String)} at the first world frame.</li>
 * </ul>
 *
 * One instance is created per config; the recording state is static and thread safe (Mixin applies classes from the class loader of
 * whichever thread loads them).
 */
public final class NativeMixinPlugin implements IMixinConfigPlugin {
    /** Config areas in the order of Appendix B; {@code selftest} exists but is not registered by default. */
    public static final List<String> AREAS = List.of("texture", "window", "core", "minecraft", "terrain", "mesh", "sky", "world", "entity",
            "item", "particle", "text", "gui", "screen", "container", "special", "mobs", "models", "objects", "tesr", "guisimple",
            "selftest", "guilists");

    /** Areas that can only be disabled all together with {@code fml.vulkan.native=false}. */
    public static final Set<String> INFRASTRUCTURE_AREAS = Set.of("core", "minecraft", "window", "world", "terrain", "mesh", "texture");

    /** The config registered by {@code FishModLoader.registerModloaderMixin}: every area but {@code selftest}. */
    public static List<String> registeredConfigs() {
        List<String> names = new ArrayList<>();
        for (String area : AREAS) {
            if (!"selftest".equals(area)) {
                names.add(configName(area));
            }
        }
        return names;
    }

    public static String configName(String area) {
        return "fishmodloader.native." + area + ".mixins.json";
    }

    private static final String PACKAGE_PREFIX = "net.xiaoyu233.fml.vulkan.mixin.nat.";

    /** area -> mixin class name (simple) -> applied target classes. */
    private static final Map<String, Map<String, Set<String>>> APPLIED = new ConcurrentHashMap<>();
    /** area -> mixin class names that were refused by {@link #shouldApplyMixin} (switch off). */
    private static final Map<String, Set<String>> DISABLED = new ConcurrentHashMap<>();

    private static final java.util.concurrent.atomic.AtomicBoolean HOOK_INSTALLED = new java.util.concurrent.atomic.AtomicBoolean();

    private String area = "";

    @Override
    public void onLoad(String mixinPackage) {
        if (VulkanConfig.nativeAudit() && HOOK_INSTALLED.compareAndSet(false, true)) {
            // every class the game loads is transformed by then: the file is written when the JVM exits (a testbot or NR-28 can also
            // call writeAuditIfEnabled at the first world frame, the later write overwrites this one)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> writeAuditIfEnabled(System.getProperty("fml.vulkan.testbot.tag", "run")),
                    "FML-Native-MixinAudit"));
        }
        String pkg = mixinPackage.endsWith(".") ? mixinPackage.substring(0, mixinPackage.length() - 1) : mixinPackage;
        this.area = pkg.startsWith(PACKAGE_PREFIX.substring(0, PACKAGE_PREFIX.length() - 1))
                ? pkg.substring(Math.min(pkg.length(), PACKAGE_PREFIX.length())) : pkg;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean apply = areaActive(area);
        if (!apply) {
            DISABLED.computeIfAbsent(area, k -> ConcurrentHashMap.newKeySet()).add(simpleName(mixinClassName));
        }
        return apply;
    }

    /** True when the mixins of {@code area} are applied under the current switches. */
    public static boolean areaActive(String area) {
        return VulkanConfig.areaActive(area);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        APPLIED.computeIfAbsent(area, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(simpleName(mixinClassName), k -> ConcurrentHashMap.newKeySet())
                .add(targetClassName);
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    // ---------------------------------------------------------------------------------------------------------
    // audit
    // ---------------------------------------------------------------------------------------------------------

    /** Number of recorded applications (mixin x target pairs). */
    public static int appliedCount() {
        int n = 0;
        for (Map<String, Set<String>> m : APPLIED.values()) {
            for (Set<String> t : m.values()) {
                n += t.size();
            }
        }
        return n;
    }

    /** Mixins of the enabled native areas that are listed in their config but were not applied yet (area/Mixin). */
    public static List<String> missing() {
        List<String> missing = new ArrayList<>();
        for (String area : AREAS) {
            if (!areaActive(area) || "selftest".equals(area)) {
                continue;
            }
            Map<String, Set<String>> applied = APPLIED.getOrDefault(area, Collections.emptyMap());
            for (String mixin : listedMixins(area)) {
                if (!applied.containsKey(mixin)) {
                    missing.add(area + "/" + mixin);
                }
            }
        }
        return missing;
    }

    /** Writes the mixin apply audit when {@code fml.vulkan.native.audit=true}; {@code tag} names the file {@code mixin-audit-<tag>.md}. */
    public static void writeAuditIfEnabled(String tag) {
        if (!VulkanConfig.nativeAudit()) {
            return;
        }
        String docsProperty = System.getProperty("fml.vulkan.testbot.docs");
        Path docsDir = docsProperty != null ? Path.of(docsProperty) : VulkanConfig.gameDir().resolve("native-audit");
        Path docs = docsDir.resolve("native").resolve("mixin-audit-" + tag + ".md");
        try {
            writeAudit(docs);
        } catch (IOException e) {
            VkLog.LOGGER.warn("Cannot write the mixin audit {}: {}", docs, e.toString());
        }
    }

    /** Writes the audit table (expected vs applied per area) and logs a summary; returns true when nothing is missing. */
    public static boolean writeAudit(Path markdown) throws IOException {
        List<String> missing = missing();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# Native mixin apply audit\n\n");
        sb.append("fml.vulkan.native=").append(VulkanConfig.nativeEnabled())
                .append(", fml.vulkan.native.off=`").append(System.getProperty(VulkanConfig.PROP_NATIVE_OFF, "")).append("`\n\n");
        sb.append("Result: **").append(missing.isEmpty() ? "OK" : "FAIL (" + missing.size() + " missing)").append("**, ")
                .append(appliedCount()).append(" mixin/target applications recorded\n\n");
        sb.append("| area | state | expected | applied | missing |\n|---|---|---|---|---|\n");
        for (String area : AREAS) {
            List<String> listed = listedMixins(area);
            Map<String, Set<String>> applied = APPLIED.getOrDefault(area, Collections.emptyMap());
            String state = "selftest".equals(area) ? "not registered" : areaActive(area) ? "on" : "off";
            List<String> miss = new ArrayList<>();
            if ("on".equals(state)) {
                for (String mixin : listed) {
                    if (!applied.containsKey(mixin)) {
                        miss.add(mixin);
                    }
                }
            }
            sb.append("| ").append(area).append(" | ").append(state).append(" | ").append(listed.size()).append(" | ")
                    .append(applied.size()).append(" | ").append(miss.isEmpty() ? "-" : String.join(", ", miss)).append(" |\n");
        }
        sb.append("\n## Applied\n\n");
        Map<String, Set<String>> sorted = new TreeMap<>();
        for (Map.Entry<String, Map<String, Set<String>>> area : APPLIED.entrySet()) {
            for (Map.Entry<String, Set<String>> mixin : area.getValue().entrySet()) {
                sorted.put(area.getKey() + "/" + mixin.getKey(), new TreeSet<>(mixin.getValue()));
            }
        }
        for (Map.Entry<String, Set<String>> e : sorted.entrySet()) {
            sb.append("* ").append(e.getKey()).append(" -> ").append(String.join(", ", e.getValue())).append('\n');
        }
        if (markdown.getParent() != null) {
            Files.createDirectories(markdown.getParent());
        }
        Files.writeString(markdown, sb.toString());
        if (missing.isEmpty()) {
            VkLog.LOGGER.info("Native mixin audit OK: {}", markdown);
            System.err.println("[FML-Native] mixin audit OK: " + markdown);
        } else {
            VkLog.LOGGER.error("Native mixin audit FAILED, missing: {} ({})", missing, markdown);
            System.err.println("[FML-Native] mixin audit FAILED, missing " + missing.size() + " (" + markdown + ")");
        }
        return missing.isEmpty();
    }

    /** The {@code client} entries of the config of {@code area}, read from the class path resource. */
    static List<String> listedMixins(String area) {
        ClassLoader loader = NativeMixinPlugin.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(configName(area))) {
            if (in == null) {
                return List.of();
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray client = root.has("client") ? root.getAsJsonArray("client") : new JsonArray();
            String[] names = new String[client.size()];
            int i = 0;
            for (JsonElement e : client) {
                names[i++] = e.getAsString();
            }
            return Arrays.asList(names);
        } catch (IOException | RuntimeException e) {
            VkLog.LOGGER.warn("Cannot read the native mixin config of area {}: {}", area, e.toString());
            return List.of();
        }
    }
}
