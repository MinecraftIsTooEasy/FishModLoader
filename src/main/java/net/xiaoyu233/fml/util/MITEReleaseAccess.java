package net.xiaoyu233.fml.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.Arrays;

public class MITEReleaseAccess {
    public static final int miteRelease;
    public static final int[] normal_releases = new int[]{153, 155, 156, 157, 162, 163, 167, 168, 171, 173, 174, 179, 180, 181, 183, 186, 187, 190, 191, 194, 195};

    static {
        try {
            byte[] data = MITEReleaseAccess.class.getResourceAsStream("/net/minecraft/Minecraft.class").readAllBytes();
            final int[] version = new int[1];
            ClassReader cr = new ClassReader(data);
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    if ("MITE_release_number".equals(name))
                        version[0] = (int) value;
                    return super.visitField(access, name, descriptor, signature, value);
                }
            }, ClassReader.EXPAND_FRAMES);
            miteRelease = version[0];
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isExperimental() {
        return Arrays.stream(normal_releases).noneMatch(i -> i == miteRelease);
    }
}
