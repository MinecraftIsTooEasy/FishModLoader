package cpw.mods.fml.common;

/**
 * Minimal stub for cpw.mods.fml.common.versioning.ArtifactVersion.
 * Forge 1.6.4 lives at cpw.mods.fml.common.versioning.ArtifactVersion;
 * we expose the same name in the parent package as a shortcut for ModContainer.
 *
 * The full interface is deliberately small here — most mod code only checks
 * {@link #getLabel()} and {@link #getVersionString()}.
 */
public interface ArtifactVersion {
    String getLabel();
    String getVersionString();
    String getRangeString();
    boolean containsVersion(ArtifactVersion source);
}
