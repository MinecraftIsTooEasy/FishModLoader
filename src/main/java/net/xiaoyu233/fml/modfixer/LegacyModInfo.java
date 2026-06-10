package net.xiaoyu233.fml.modfixer;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Result of scanning a jar for a Forge {@code @Mod} annotation.
 * Carries the source jar path, the FQCN of the mod class, and the raw
 * annotation values (modid, name, version, dependencies, ...).
 */
public final class LegacyModInfo {
    private final Path jarPath;
    private final String modClass;
    private final Map<String, Object> annotationValues;

    public LegacyModInfo(Path jarPath, String modClass, Map<String, Object> annotationValues) {
        this.jarPath = jarPath;
        this.modClass = modClass;
        this.annotationValues = Collections.unmodifiableMap(annotationValues);
    }

    public Path getJarPath() { return jarPath; }
    public String getModClass() { return modClass; }
    public Map<String, Object> getAnnotationValues() { return annotationValues; }

    public String getModId() {
        Object v = annotationValues.get("modid");
        return v == null ? null : v.toString();
    }

    public String getName() {
        Object v = annotationValues.get("name");
        return v == null ? getModId() : v.toString();
    }

    public String getVersion() {
        Object v = annotationValues.get("version");
        return v == null ? "0.0.0" : v.toString();
    }

    public String getDependencies() {
        Object v = annotationValues.get("dependencies");
        return v == null ? "" : v.toString();
    }

    public String getAcceptedMinecraftVersions() {
        Object v = annotationValues.get("acceptedMinecraftVersions");
        return v == null ? "" : v.toString();
    }

    @Override
    public String toString() {
        return "LegacyModInfo[" + getModId() + " v" + getVersion() + " in " + jarPath.getFileName() + "]";
    }
}
