package net.xiaoyu233.fml.modfixer;

import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

public final class LegacyForgeModRemapperProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            buildLifecycleFixture(Path.of(args[0]));
            System.out.println("Built official Forge remap lifecycle fixture: " + args[0]);
            return;
        }
        Path root=Files.createTempDirectory("legacy-forge-remap-probe-");
        try {
            Path game=jar(root.resolve("game.jar"), Map.of("net/minecraft/block/Block.class", gameClass(),"net/minecraft/entity/Entity.class",gameEntityClass()), false);
            Path official=jar(root.resolve("official.jar"), fixture("aqz","aqz","cF","d"), true);
            Path intermediary=jar(root.resolve("intermediary.jar"), fixture("net/minecraft/block/Block","net/minecraft/block/Block","field_71990_ca","func_71857_b"), false);
            Path unknown=jar(root.resolve("unknown.jar"), Map.of("fixture/Plain.class", plainClass()), false);
            Map<String,byte[]> mixedEntries=new LinkedHashMap<>(fixture("aqz","net/minecraft/block/Block","cF","d"));
            Path mixed=jar(root.resolve("mixed.jar"),mixedEntries,false);
            LegacyForgeModRemapper remapper=new LegacyForgeModRemapper();
            check(remapper.scanNamespace(official)==LegacyForgeModRemapper.Namespace.OFFICIAL,"official scan");
            check(remapper.scanNamespace(intermediary)==LegacyForgeModRemapper.Namespace.INTERMEDIARY,"intermediary scan");
            check(remapper.scanNamespace(unknown)==LegacyForgeModRemapper.Namespace.UNKNOWN_NO_GAME_REFS,"unknown scan");
            check(remapper.scanNamespace(mixed)==LegacyForgeModRemapper.Namespace.MIXED,"mixed scan");
            Path gameDir=root.resolve("run");
            LegacyForgeModRemapper.Result first=remapper.prepare(official,game,gameDir);
            check(!first.cacheHit && !first.runtimePath.equals(first.sourcePath),"cache miss remap");
            check(remapper.scanNamespace(first.runtimePath)==LegacyForgeModRemapper.Namespace.INTERMEDIARY,"output namespace");
            verifyClass(read(first.runtimePath,"fixture/OfficialMod.class"));
            try(JarFile jf=new JarFile(first.runtimePath.toFile())) {
                check(jf.getJarEntry("assets/fixture.txt")!=null,"resource retained");
                check(jf.getJarEntry("assets/fixture/textures/blocks/namespaced.png")!=null,"namespaced texture retained");
                check("fixture-value".equals(jf.getManifest().getMainAttributes().getValue("Fixture-Attr")),"manifest retained");
                check(jf.getJarEntry("META-INF/TEST.SF")==null,"signature removed");
            }
            LegacyForgeModRemapper.Result second=remapper.prepare(official,game,gameDir);
            check(second.cacheHit && Arrays.equals(Files.readAllBytes(first.runtimePath),Files.readAllBytes(second.runtimePath)),"cache hit bytes");
            check(remapper.prepare(intermediary,game,gameDir).runtimePath.equals(intermediary.toRealPath()),"intermediary passthrough");
            check(remapper.prepare(unknown,game,gameDir).runtimePath.equals(unknown.toRealPath()),"unknown passthrough");
            boolean rejected=false;try{remapper.prepare(mixed,game,gameDir);}catch(IOException e){rejected=e.getMessage().contains("mixed namespace");}check(rejected,"mixed fail closed");
            byte[] mappings=LegacyForgeModRemapperProbe.class.getResourceAsStream("/intermediary.tiny").readAllBytes();
            LegacyForgeModRemapper schema2=new LegacyForgeModRemapper(mappings,"probe-schema-change");
            Path changed=schema2.prepare(official,game,gameDir).runtimePath;
            check(!changed.equals(first.runtimePath),"schema changes key");
            Path brokenGame=root.resolve("missing-game.jar");
            long before=countJars(gameDir);rejected=false;try{remapper.prepare(official,brokenGame,gameDir);}catch(IOException e){rejected=true;}check(rejected && countJars(gameDir)==before,"failure publishes no cache");
            System.out.println("Legacy Forge mod remapper probe passed: "+root);
        } finally { delete(root); }
    }

    private static void buildLifecycleFixture(Path output) throws Exception {
        Files.createDirectories(output.getParent()); Files.deleteIfExists(output);
        jar(output, Map.of("fixture/remap/OfficialLifecycleMod.class", lifecycleClass()), false);
    }
    private static byte[] lifecycleClass() {
        ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/remap/OfficialLifecycleMod",null,"java/lang/Object",null);
        AnnotationVisitor mod=w.visitAnnotation("Lcpw/mods/fml/common/Mod;",true);mod.visit("modid","forge_remap_fixture");mod.visit("name","Forge Remap Fixture");mod.visit("version","1.0");mod.visitEnd();
        w.visitField(Opcodes.ACC_PUBLIC,"officialBlockReference","Laqz;",null,null).visitEnd();
        MethodVisitor init=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);init.visitInsn(Opcodes.RETURN);init.visitMaxs(1,1);init.visitEnd();
        lifecycleMethod(w,"preInit","Lcpw/mods/fml/common/event/FMLPreInitializationEvent;","PREINIT");
        lifecycleMethod(w,"init","Lcpw/mods/fml/common/event/FMLInitializationEvent;","INIT");
        lifecycleMethod(w,"postInit","Lcpw/mods/fml/common/event/FMLPostInitializationEvent;","POSTINIT");
        w.visitEnd();return w.toByteArray();
    }
    private static void lifecycleMethod(ClassWriter w,String name,String event,String marker) {
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC,name,"("+event+")V",null,null);
        m.visitAnnotation("Lcpw/mods/fml/common/Mod$EventHandler;",true).visitEnd();m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC,"java/lang/System","out","Ljava/io/PrintStream;");m.visitLdcInsn("[Forge remap fixture] "+marker+" PASSED");m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/io/PrintStream","println","(Ljava/lang/String;)V",false);m.visitInsn(Opcodes.RETURN);m.visitMaxs(2,2);m.visitEnd();
    }

    private static Map<String,byte[]> fixture(String superName,String referencedOwner,String field,String method) {
        Map<String,byte[]> m=new LinkedHashMap<>();
        m.put("fixture/OfficialMod.class",modClass(superName,referencedOwner,field,method));
        m.put("assets/fixture.txt","ok".getBytes());
        // Repository-owned 1x1 PNG under a non-Minecraft resource namespace.
        m.put("assets/fixture/textures/blocks/namespaced.png", Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
        return m;
    }
    private static byte[] modClass(String superName,String owner,String field,String method) {
        ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/OfficialMod",null,superName,null);
        w.visitField(Opcodes.ACC_PUBLIC,"block","L"+owner+";",null,null).visitEnd();
        MethodVisitor init=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,superName,"<init>","()V",false);init.visitInsn(Opcodes.RETURN);init.visitMaxs(1,1);init.visitEnd();
        boolean officialNames="aqz".equals(superName);
        MethodVisitor inherited=w.visitMethod(Opcodes.ACC_PUBLIC,"inheritedField","()V",null,null);inherited.visitCode();inherited.visitVarInsn(Opcodes.ALOAD,0);inherited.visitInsn(Opcodes.ICONST_1);inherited.visitFieldInsn(Opcodes.PUTFIELD,"fixture/OfficialMod",officialNames?"cF":"field_71990_ca","I");inherited.visitInsn(Opcodes.RETURN);inherited.visitMaxs(2,1);inherited.visitEnd();
        MethodVisitor mv=w.visitMethod(Opcodes.ACC_PUBLIC,"exercise","(L"+owner+";)L"+owner+";",null,null);mv.visitCode();mv.visitVarInsn(Opcodes.ALOAD,1);mv.visitFieldInsn(Opcodes.GETFIELD,owner,field,"I");mv.visitInsn(Opcodes.POP);mv.visitVarInsn(Opcodes.ALOAD,1);mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,owner,method,"()I",false);mv.visitInsn(Opcodes.POP);mv.visitVarInsn(Opcodes.ALOAD,1);mv.visitInsn(Opcodes.ARETURN);mv.visitMaxs(1,2);mv.visitEnd();
        // Legacy Block overrides: icon namespace plus deterministic harvest marker
        // and idDropped=0 to replace the vanilla default drop. Keep the synthetic
        // intermediary input pure so namespace classification remains meaningful.
        String iconType=officialNames?"mt":"net/minecraft/client/renderer/texture/IconRegister";
        String iconResult=officialNames?"ms":"net/minecraft/util/Icon";
        String worldType=officialNames?"abw":"net/minecraft/world/World";
        String playerType=officialNames?"uf":"net/minecraft/entity/player/EntityPlayer";
        MethodVisitor icons=w.visitMethod(Opcodes.ACC_PUBLIC,officialNames?"a":"func_94332_a","(L"+iconType+";)V",null,null);icons.visitCode();icons.visitVarInsn(Opcodes.ALOAD,0);icons.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"fixture/OfficialMod",officialNames?"a":"func_71917_a","()Ljava/lang/String;",false);icons.visitInsn(Opcodes.POP);icons.visitVarInsn(Opcodes.ALOAD,1);icons.visitLdcInsn("fixture:namespaced");icons.visitMethodInsn(Opcodes.INVOKEINTERFACE,iconType,officialNames?"a":"func_94245_a","(Ljava/lang/String;)L"+iconResult+";",true);icons.visitInsn(Opcodes.POP);icons.visitInsn(Opcodes.RETURN);icons.visitMaxs(2,2);icons.visitEnd();
        MethodVisitor harvest=w.visitMethod(Opcodes.ACC_PUBLIC,officialNames?"a":"func_71893_a","(L"+worldType+";L"+playerType+";IIII)V",null,null);harvest.visitCode();harvest.visitFieldInsn(Opcodes.GETSTATIC,"java/lang/System","out","Ljava/io/PrintStream;");harvest.visitLdcInsn("[Forge legacy block fixture] HARVEST CALLBACK PASSED");harvest.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/io/PrintStream","println","(Ljava/lang/String;)V",false);harvest.visitInsn(Opcodes.RETURN);harvest.visitMaxs(2,7);harvest.visitEnd();
        MethodVisitor dropped=w.visitMethod(Opcodes.ACC_PUBLIC,officialNames?"a":"func_71885_a","(ILjava/util/Random;I)I",null,null);dropped.visitCode();dropped.visitInsn(Opcodes.ICONST_0);dropped.visitInsn(Opcodes.IRETURN);dropped.visitMaxs(1,4);dropped.visitEnd();
        w.visitEnd();return w.toByteArray();
    }
    private static byte[] gameEntityClass(){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"net/minecraft/entity/Entity",null,"java/lang/Object",null);w.visitField(Opcodes.ACC_PUBLIC,"field_70177_z","F",null,null).visitEnd();w.visitEnd();return w.toByteArray();}
    private static byte[] gameClass(){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"net/minecraft/block/Block",null,"java/lang/Object",null);w.visitField(Opcodes.ACC_PUBLIC,"field_71990_ca","I",null,null).visitEnd();MethodVisitor i=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);i.visitCode();i.visitVarInsn(Opcodes.ALOAD,0);i.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);i.visitInsn(Opcodes.RETURN);i.visitMaxs(1,1);i.visitEnd();MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC,"func_71857_b","()I",null,null);m.visitCode();m.visitInsn(Opcodes.ICONST_0);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(1,1);m.visitEnd();w.visitEnd();return w.toByteArray();}
    private static byte[] plainClass(){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/Plain",null,"java/lang/Object",null);w.visitEnd();return w.toByteArray();}
    private static Path jar(Path p,Map<String,byte[]> entries,boolean signed)throws Exception{Manifest mf=new Manifest();mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");mf.getMainAttributes().putValue("Fixture-Attr","fixture-value");try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(p),mf)){for(Map.Entry<String,byte[]> e:entries.entrySet()){out.putNextEntry(new JarEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}if(signed){out.putNextEntry(new JarEntry("META-INF/TEST.SF"));out.write("Signature-Version: 1.0\n".getBytes());out.closeEntry();}}return p;}
    private static byte[] read(Path jar,String name)throws Exception{try(JarFile jf=new JarFile(jar.toFile());InputStream in=jf.getInputStream(jf.getJarEntry(name))){return in.readAllBytes();}}
    private static void verifyClass(byte[] b){final boolean[] field={false},method={false},inherited={false},icons={false},iconName={false},harvest={false},drop={false};new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9){@Override public void visit(int v,int a,String n,String s,String sup,String[] i){check("net/minecraft/block/Block".equals(sup),"super remapped");}@Override public FieldVisitor visitField(int a,String n,String d,String s,Object v){if(n.equals("block"))field[0]=d.equals("Lnet/minecraft/block/Block;");return null;}@Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){if(n.equals("func_94332_a")&&d.equals("(Lnet/minecraft/client/renderer/texture/IconRegister;)V"))icons[0]=true;if(n.equals("func_71893_a")&&d.equals("(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;IIII)V"))harvest[0]=true;if(n.equals("func_71885_a")&&d.equals("(ILjava/util/Random;I)I"))drop[0]=true;if(n.equals("inheritedField"))return new MethodVisitor(Opcodes.ASM9){@Override public void visitFieldInsn(int op,String o,String fn,String fd){if(fn.equals("field_71990_ca"))inherited[0]=true;}};if(n.equals("func_94332_a"))return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int op,String o,String mn,String md,boolean itf){if(mn.equals("func_71917_a")&&md.equals("()Ljava/lang/String;"))iconName[0]=true;}};if(!n.equals("exercise"))return null;check(d.equals("(Lnet/minecraft/block/Block;)Lnet/minecraft/block/Block;"),"method descriptor");return new MethodVisitor(Opcodes.ASM9){@Override public void visitFieldInsn(int op,String o,String n,String d){if(o.equals("net/minecraft/block/Block")&&n.equals("field_71990_ca"))method[0]=true;}@Override public void visitMethodInsn(int op,String o,String n,String d,boolean itf){if(o.equals("net/minecraft/block/Block")&&n.equals("func_71857_b"))method[0]=true;}};}},0);check(field[0]&&method[0]&&inherited[0],"field/method remapped");check(icons[0]&&iconName[0]&&harvest[0]&&drop[0],"legacy Block overrides remapped");}
    private static long countJars(Path gameDir)throws Exception{Path d=gameDir.resolve(".fml/remappedForgeMods");if(!Files.exists(d))return 0;try(var s=Files.list(d)){return s.filter(p->p.toString().endsWith(".jar")).count();}}
    private static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    private static void delete(Path p)throws IOException{if(!Files.exists(p))return;try(var s=Files.walk(p)){s.sorted(Comparator.reverseOrder()).forEach(x->{try{Files.deleteIfExists(x);}catch(IOException e){throw new UncheckedIOException(e);}});}}
}
