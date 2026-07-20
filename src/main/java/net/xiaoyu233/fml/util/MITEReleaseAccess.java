package net.xiaoyu233.fml.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class MITEReleaseAccess {
    public static final int miteRelease;
    public static final int[] normal_releases = new int[]{153, 155, 156, 157, 162, 163, 167, 168, 171, 173, 174, 179, 180, 181, 183, 186, 187, 190, 191, 194, 195};

    static {
        // Try multiple classloaders because the MITE jar isn't on the
        // bootstrap/system classpath in production launches — the FML
        // launcher loads it later via Knot. Fall back to 0 if it's still
        // not visible (callers will treat 0 as "unknown release").
        int discovered = 0;
        try {
            InputStream in = locateMinecraftClass();
            if (in != null) {
                byte[] data = in.readAllBytes();
                final int[] version = new int[1];
                ClassReader cr = new ClassReader(data);
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        if ("MITE_release_number".equals(name) && value instanceof Integer)
                            version[0] = (int) value;
                        return super.visitField(access, name, descriptor, signature, value);
                    }
                }, ClassReader.EXPAND_FRAMES);
                discovered = version[0];
            } else {
                System.err.println("[FishModLoader] WARNING: Minecraft.class not yet on classpath; MITE release detection will return 0");
            }
        } catch (IOException e) {
            System.err.println("[FishModLoader] WARNING: failed to read Minecraft.class for MITE release detection: " + e);
        }
        miteRelease = discovered;
    }

    private static InputStream locateMinecraftClass() {
        String resource = "net/minecraft/client/atv.class";
        // 1. classloader of this class
        InputStream in = MITEReleaseAccess.class.getClassLoader() != null
                ? MITEReleaseAccess.class.getClassLoader().getResourceAsStream(resource)
                : null;
        if (in != null) return in;
        // 2. context classloader (Knot/FML usually sets this once MITE is loaded)
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            in = ctx.getResourceAsStream(resource);
            if (in != null) return in;
        }
        // 3. system classloader as last resort
        return ClassLoader.getSystemResourceAsStream(resource);
    }

    public static boolean isExperimental() {
        return Arrays.stream(normal_releases).noneMatch(i -> i == miteRelease);
    }
}
