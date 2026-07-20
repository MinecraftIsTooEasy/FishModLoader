package org.moddedmite.fish.faloom;

import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerClassVisitor;
import net.fabricmc.accesswidener.AccessWidenerReader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.objectweb.asm.Opcodes.ASM9;

public final class ApplyAccessWidener {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: ApplyAccessWidener <input.jar> <output.jar> <accesswidener>");
            System.exit(1);
        }
        Path inputJar = Paths.get(args[0]);
        Path outputJar = Paths.get(args[1]);
        Path awFile = Paths.get(args[2]);

        AccessWidener widener = new AccessWidener();
        AccessWidenerReader reader = new AccessWidenerReader(widener);
        try (BufferedReader br = Files.newBufferedReader(awFile, StandardCharsets.UTF_8)) {
            reader.read(br, "named");
        }
        System.out.println("Loaded " + widener.getTargets().size() + " AW targets from " + awFile);

        int widened = 0;
        outputJar.getParent().toFile().mkdirs();
        try (ZipFile zf = new ZipFile(inputJar.toFile());
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outputJar)))) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry inEntry = entries.nextElement();
                ZipEntry outEntry = new ZipEntry(inEntry.getName());
                outEntry.setTime(inEntry.getTime());
                zos.putNextEntry(outEntry);
                byte[] data = readAllBytes(zf.getInputStream(inEntry));
                if (inEntry.getName().endsWith(".class")) {
                    String className = inEntry.getName().replace('/', '.').replace(".class", "");
                    if (widener.getTargets().contains(className)) {
                        ClassReader cr = new ClassReader(data);
                        ClassWriter cw = new ClassWriter(cr, 0);
                        ClassVisitor awVisitor = AccessWidenerClassVisitor.createClassVisitor(ASM9, cw, widener);
                        cr.accept(awVisitor, 0);
                        data = cw.toByteArray();
                        widened++;
                    }
                }

                zos.write(data);
                zos.closeEntry();
            }
        }
        System.out.println("Done. Widened " + widened + " classes.");
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Short.MAX_VALUE);
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) >= 0) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }
}
