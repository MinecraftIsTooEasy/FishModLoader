package net.xiaoyu233.fml.vulkan.render.legacy;

import java.io.File;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Forces loading (not initialisation) of every net.minecraft.* class of the game jar so that the post-mixin class export
 * (run/.mixin.out) contains all of them for the transformed GL-site scan (ARCHITECTURE-NATIVE 1.16.6). Active only with
 * {@code -Dfml.vulkan.forceLoadGameClasses=true}. Must be called once after the world is entered; no call site exists in this tree yet
 * (contractRequest: the testbot world-entered step or NR-07/NR-28 must call {@link #run()}), without it the transformed scan is incomplete.
 */
public final class ForceLoadGameClasses {
    public static final String PROPERTY = "fml.vulkan.forceLoadGameClasses";
    private static boolean done;

    private ForceLoadGameClasses() {
    }

    public static synchronized void run() {
        if (done || !Boolean.getBoolean(PROPERTY)) {
            return;
        }
        done = true;
        int ok = 0;
        int failed = 0;
        int total = 0;
        try {
            ClassLoader loader = ForceLoadGameClasses.class.getClassLoader();
            Class<?> mc = Class.forName("net.minecraft.Minecraft", false, loader);
            CodeSource cs = mc.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                System.err.println("[ForceLoadGameClasses] no code source for net.minecraft.Minecraft");
                return;
            }
            File jar = new File(cs.getLocation().toURI());
            try (ZipFile zf = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (!n.startsWith("net/minecraft/") || !n.endsWith(".class")) {
                        continue;
                    }
                    total++;
                    try {
                        Class.forName(n.substring(0, n.length() - 6).replace('/', '.'), false, loader);
                        ok++;
                    } catch (Throwable t) {
                        failed++;
                    }
                }
            }
        } catch (URISyntaxException | java.io.IOException | ClassNotFoundException | RuntimeException e) {
            System.err.println("[ForceLoadGameClasses] aborted: " + e);
        }
        System.out.println("[ForceLoadGameClasses] classes=" + total + " loaded=" + ok + " failed=" + failed);
    }
}
