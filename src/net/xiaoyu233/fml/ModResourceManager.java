package net.xiaoyu233.fml;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashSet;
import java.util.Set;

public class ModResourceManager {
    public static final Set resourceDomains = new HashSet<>();

    @Environment(EnvType.CLIENT)
    public static void addResourcePackDomain(String domain) {
        resourceDomains.add(domain);
    }

    public static Set getResourceDomains() {
        return resourceDomains;
    }
}
