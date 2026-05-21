package net.xiaoyu233.fml;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashSet;
import java.util.Set;

public class ModResourceManager {
    public static final Set<String> namespaces = new HashSet<>();
    public static final Set<String> jsonNamespaces = new HashSet<>();

    @Environment(EnvType.CLIENT)
    public static void addResourcePackDomain(String namespace) {
        namespaces.add(namespace);
    }

    /**
     * Allows loading JSON language files under a specific namespace
     */
    @Environment(EnvType.CLIENT)
    public static void ableToJsonLang(String namespace) {
        jsonNamespaces.add(namespace);
    }

    public static Set<String> getNamespaces() {
        return namespaces;
    }

    public static Set<String> getJsonNamespaces() {
        return namespaces;
    }
}
