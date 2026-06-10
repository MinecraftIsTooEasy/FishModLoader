package cpw.mods.fml.common;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stub for cpw.mods.fml.common.DummyModContainer (Forge 1.6.4).
 * A no-behaviour ModContainer used for container slots that need a name
 * but no real lifecycle.
 */
public class DummyModContainer implements ModContainer {
    private final ModMetadata metadata;
    private File source;

    public DummyModContainer() {
        this(new ModMetadata());
    }

    public DummyModContainer(ModMetadata metadata) {
        this.metadata = metadata;
    }

    @Override public String getModId()      { return metadata.modId; }

    @Override public String getName()       { return metadata.name; }

    @Override public String getVersion()    { return metadata.version; }

    @Override public File getSource()       { return source; }

    public void setSource(File source) { this.source = source; }

    @Override public ModMetadata getMetadata() { return metadata; }
    @Override public Object getMod()        { return null; }
    @Override public Set<ArtifactVersion> getDependencies() { return new HashSet<>(); }
    @Override public List<ArtifactVersion> getDependants()  { return Collections.emptyList(); }
    @Override public Set<ArtifactVersion> getRequirements() { return new HashSet<>(); }
    @Override public String getSortingRules() { return ""; }
    @Override public boolean matches(Object mod) { return false; }
    @Override public String getDisplayVersion() { return getVersion(); }
}
