package net.xiaoyu233.fml.modfixer;

import cpw.mods.fml.common.ArtifactVersion;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Concrete {@link ModContainer} for a discovered Forge 1.6.4 mod.
 *
 * <p>Holds the parsed metadata, the source jar, and the live mod instance
 * created by {@link LegacyModLifecycle}. Most of the dependency-graph
 * methods return empty collections for now — full dependency parsing
 * (Forge's "before:foo;after:bar;required-after:baz" syntax) lives in
 * stage 4b once we want to enforce ordering.
 */
public final class ForgeModContainer implements ModContainer {
    private final ModMetadata metadata;
    private final File source;
    private final Object modInstance;
    private final Class<?> modClass;

    public ForgeModContainer(ModMetadata metadata, File source, Object modInstance, Class<?> modClass) {
        this.metadata    = metadata;
        this.source      = source;
        this.modInstance = modInstance;
        this.modClass    = modClass;
    }

    public Class<?> getModClass() { return modClass; }

    @Override public String getModId()      { return metadata.modId; }
    @Override public String getName()       { return metadata.name; }
    @Override public String getVersion()    { return metadata.version; }
    @Override public File getSource()       { return source; }
    @Override public ModMetadata getMetadata() { return metadata; }
    @Override public Object getMod()        { return modInstance; }

    @Override public Set<ArtifactVersion> getDependencies() { return new HashSet<>(); }
    @Override public List<ArtifactVersion> getDependants()  { return Collections.emptyList(); }
    @Override public Set<ArtifactVersion> getRequirements() { return new HashSet<>(); }
    @Override public String getSortingRules() { return ""; }
    @Override public boolean matches(Object mod) { return mod == modInstance; }
    @Override public String getDisplayVersion() { return getVersion(); }
}
