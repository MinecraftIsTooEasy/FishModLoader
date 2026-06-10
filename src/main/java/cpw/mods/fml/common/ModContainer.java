package cpw.mods.fml.common;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Stub for cpw.mods.fml.common.ModContainer (Forge 1.6.4).
 *
 * Subset of the original interface; methods that aren't called by typical
 * mod code may return null/empty defaults.
 */
public interface ModContainer {

    String getModId();

    String getName();

    String getVersion();

    File getSource();

    ModMetadata getMetadata();

    Object getMod();

    Set<ArtifactVersion> getDependencies();

    List<ArtifactVersion> getDependants();

    Set<ArtifactVersion> getRequirements();

    String getSortingRules();

    boolean matches(Object mod);

    String getDisplayVersion();
}
