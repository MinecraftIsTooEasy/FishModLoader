package net.xiaoyu233.fml.modfixer;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.MetadataCollection;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;
import cpw.mods.fml.common.event.FMLStateEvent;
import cpw.mods.fml.common.versioning.ArtifactVersion;
import cpw.mods.fml.common.versioning.VersionRange;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.cert.Certificate;
import java.util.*;
import java.util.logging.Level;

/**
 * Concrete {@link ModContainer} for a discovered Forge 1.6.4 mod.
 *
 * <p>Holds the parsed metadata, the source jar, and the live mod instance
 * created by {@link LegacyModLifecycle}. Most of the dependency-graph
 * methods return empty collections for now — full dependency parsing
 * (Forge's "before:foo;after:bar;required-after:baz" syntax) lives in
 * stage 4b once we want to enforce ordering.
 */
@Deprecated
public final class ForgeModContainer implements ModContainer {
    private final ModMetadata metadata;
    private final File source;
    private final Object modInstance;
    private final Class<?> modClass;
    private final Map<String, List<Method>> handlerMethods = new HashMap<>();
    private LoadController controller;

    public ForgeModContainer(ModMetadata metadata, File source, Object modInstance, Class<?> modClass) {
        this.metadata    = metadata;
        this.source      = source;
        this.modInstance = modInstance;
        this.modClass    = modClass;
    }

    public Class<?> getModClass() { return modClass; }

    /** Register a handler method for the given FML event class name. */
    public void addHandler(String eventClassName, Method method) {
        handlerMethods.computeIfAbsent(eventClassName, k -> new ArrayList<>()).add(method);
    }

    /** Get all registered handler methods keyed by event class name. */
    public Map<String, List<Method>> getHandlerMethods() {
        return handlerMethods;
    }

    @Override public String getModId()      { return metadata.modId; }
    @Override public String getName()       { return metadata.name; }
    @Override public String getVersion()    { return metadata.version; }
    @Override public File getSource()       { return source; }
    @Override public ModMetadata getMetadata() { return metadata; }
    @Override public Object getMod()        { return modInstance; }

    @Override public void bindMetadata(MetadataCollection mc) {}
    @Override public void setEnabledState(boolean enabled) {}

    @Override public Set<ArtifactVersion> getRequirements()  { return Collections.emptySet(); }
    @Override public List<ArtifactVersion> getDependencies() { return Collections.emptyList(); }
    @Override public List<ArtifactVersion> getDependants()   { return Collections.emptyList(); }
    @Override public String getSortingRules() { return ""; }
    @Override public boolean registerBus(EventBus bus, LoadController controller) {
        this.controller = controller;
        bus.register(this);
        return true;
    }
    @Override public boolean matches(Object mod) {
        return mod == modInstance || modClass.isInstance(mod);
    }
    @Override public ArtifactVersion getProcessedVersion() { return null; }
    @Override public boolean isImmutable() { return false; }
    @Override public boolean isNetworkMod() { return false; }
    @Override public String getDisplayVersion() { return getVersion(); }
    @Override public VersionRange acceptableMinecraftVersionRange() { return null; }
    @Override public Certificate getSigningCertificate() { return null; }
    @Override public Map<String,String> getCustomModProperties() { return Collections.emptyMap(); }
    @Override public Class<?> getCustomResourcePackClass() {
        try {
            return getSource().isDirectory()
                    ? Class.forName("cpw.mods.fml.client.FMLFolderResourcePack", true, getClass().getClassLoader())
                    : Class.forName("cpw.mods.fml.client.FMLFileResourcePack", true, getClass().getClassLoader());
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
    @Override public Map<String, String> getSharedModDescriptor() { return null; }

    @Subscribe
    public void onEvent(FMLStateEvent event) {
        String eventClassName = event.getClass().getName();
        List<Method> handlers = handlerMethods.get(eventClassName);
        if (handlers == null || handlers.isEmpty()) {
            net.xiaoyu233.fml.FishModLoader.LOGGER.debug("Mod {} has no handler for {}",
                    metadata.modId, eventClassName);
            return;
        }

        net.xiaoyu233.fml.FishModLoader.LOGGER.info("Dispatching {} to {} handler(s) on Forge mod {}",
                eventClassName, handlers.size(), metadata.modId);
        for (Method handler : handlers) {
            try {
                handler.invoke(modInstance, event);
            } catch (InvocationTargetException ite) {
                // FMLLog routes through java.util.logging, which is not wired to the
                // game's log4j appenders here -- mod crashes would vanish entirely.
                net.xiaoyu233.fml.FishModLoader.LOGGER.error("Mod {} threw during {}",
                        metadata.modId, eventClassName, ite.getCause());
                if (controller != null) {
                    controller.errorOccurred(this, ite.getCause());
                }
            } catch (Throwable thrown) {
                net.xiaoyu233.fml.FishModLoader.LOGGER.error("Failed to invoke handler {} on mod {}",
                        handler.getName(), metadata.modId, thrown);
                if (controller != null) {
                    controller.errorOccurred(this, thrown);
                }
            }
        }
    }
}
