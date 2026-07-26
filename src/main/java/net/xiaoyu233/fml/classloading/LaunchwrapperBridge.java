package net.xiaoyu233.fml.classloading;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Bridges {@link net.minecraft.launchwrapper.LaunchClassLoader} to FishModLoader's
 * {@link KnotClassLoader} so that Forge code referencing
 * {@code net.minecraft.launchwrapper.Launch.classLoader} gets a working
 * classloader backed by the real Knot class-loading machinery.
 *
 * <p>Construction note: {@link LaunchClassLoader#LaunchClassLoader(URL[])} adds
 * class-loader exclusions and transformer exclusions in its own constructor.
 * We call {@code super(new URL[0])} to satisfy that requirement without
 * duplicating URLs, then every actual load is delegated to {@link #delegate}.
 */
public final class LaunchwrapperBridge extends LaunchClassLoader {

    private final ClassLoader delegate;

    public LaunchwrapperBridge(ClassLoader knotClassLoader) {
        // Pass empty URL array – real classpath is managed by KnotClassLoader.
        super(new URL[0]);
        this.delegate = knotClassLoader;
    }

    // ===== Core class loading – delegate everything to KnotClassLoader ====

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return delegate.loadClass(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> clazz = delegate.loadClass(name);
        if (resolve) resolveClass(clazz);
        return clazz;
    }

    // ===== Resource loading =====

    @Override
    public URL findResource(String name) {
        return delegate.getResource(name);
    }

    @Override
    public URL getResource(String name) {
        return delegate.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return delegate.getResources(name);
    }

    // ===== Transformer stubs – Forge mods may call these; no-ops are safe =====

    @Override
    public void registerTransformer(String transformerClassName) {
        // Transformers are handled by KnotClassDelegate/FMLClassTransformer;
        // launchwrapper-side registration is a no-op in FishModLoader.
    }

    @Override
    public List<IClassTransformer> getTransformers() {
        return Collections.emptyList();
    }

    // ===== URL management – mirror calls to the delegate's addUrl =====

    @Override
    public void addURL(URL url) {
        // Delegate URL management to KnotClassLoader so both views stay in sync.
        super.addURL(url);
        try {
            // KnotClassLoaderInterface is available via Launch.knotLoader;
            // use reflection to avoid a compile-time circular dependency.
            Class<?> launchClass = Class.forName(
                    "net.xiaoyu233.fml.relaunch.Launch",
                    false,
                    getClass().getClassLoader()
            );
            Object knotLoader = launchClass.getField("knotLoader").get(null);
            if (knotLoader != null) {
                knotLoader.getClass().getMethod("addUrl", URL.class).invoke(knotLoader, url);
            }
        } catch (Throwable ignored) {
            // Best-effort; if reflection fails, the URL is still added to super.
        }
    }

    // ===== getClassBytes / negativeResourceCache – minimal stubs =====

    @Override
    public byte[] getClassBytes(String name) throws IOException {
        String resourcePath = name.replace('.', '/') + ".class";
        URL url = getResource(resourcePath);
        if (url == null) return null;
        try (java.io.InputStream is = url.openStream()) {
            return is.readAllBytes();
        }
    }
}
