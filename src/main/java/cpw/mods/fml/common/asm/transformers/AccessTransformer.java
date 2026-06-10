package cpw.mods.fml.common.asm.transformers;

import org.objectweb.asm.tree.ClassNode;

/**
 * Stub for cpw.mods.fml.common.asm.transformers.AccessTransformer (Forge 1.6.4).
 * The real transformer applies entries from {@code _at.cfg}; this shim is
 * an empty hook to satisfy {@code instanceof} / extension checks. We use
 * Fabric's AccessWidener pipeline for the equivalent functionality.
 */
public class AccessTransformer {
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        return basicClass;
    }

    /** Process a ClassNode in place — no-op stub. */
    public void readMapFromBytes(byte[] data) {}
    public void readMapFromBytes(byte[] data, String filename) {}
    public void processClass(ClassNode node) {}
}
