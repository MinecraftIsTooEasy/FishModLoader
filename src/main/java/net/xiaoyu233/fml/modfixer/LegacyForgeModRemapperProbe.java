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
            Path game=jar(root.resolve("game.jar"), Map.of("net/minecraft/block/Block.class", gameClass(),"net/minecraft/block/BlockAnvil.class",gameAnvilClass(),"net/minecraft/entity/Entity.class",gameEntityClass()), false);
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
            verifyLuckyItemDrop(read(first.runtimePath,"mod/lucky/BlockLucky.class"));
            verifyUnsupportedLuckyLike(read(first.runtimePath,"fixture/UnsupportedLuckyLike.class"));
            byte[] mappings=LegacyForgeModRemapperProbe.class.getResourceAsStream("/intermediary.tiny").readAllBytes();
            byte[] ambiguousMappings=(new String(mappings,java.nio.charset.StandardCharsets.UTF_8)+"\nFIELD\taqz\tLsa;\tcm\tfield_ambiguous_cm\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            LegacyForgeModRemapper ambiguousRemapper=new LegacyForgeModRemapper(ambiguousMappings,"probe-ambiguous-field");
            Path ambiguousOutput=ambiguousRemapper.prepare(official,game,root.resolve("ambiguous-run")).runtimePath;
            verifyAmbiguousFieldRetained(read(ambiguousOutput,"fixture/OfficialMod.class"));
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
        if("aqz".equals(superName)) {
            m.put("mod/lucky/BlockLucky.class",luckyBlockClass());
            m.put("fixture/UnsupportedLuckyLike.class",unsupportedLuckyLikeClass());
        }
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
        chestBridgeCalls(w, officialNames);
        sandBridgeCalls(w, officialNames);
        fieldDescriptorDriftCalls(w, officialNames);
        w.visitEnd();return w.toByteArray();
    }
    private static byte[] luckyBlockClass() {
        ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"mod/lucky/BlockLucky",null,"aqz",null);
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC,"a","(Labw;Luf;IIII)V",null,null);m.visitCode();
        // One call site handles both invalid legacy ids (for example 310) and valid ids at runtime.
        // The following IINC is the group-loop continuation target for a skipped invalid drop.
        m.visitTypeInsn(Opcodes.NEW,"ss");m.visitInsn(Opcodes.DUP);m.visitVarInsn(Opcodes.ALOAD,1);
        m.visitInsn(Opcodes.DCONST_0);m.visitInsn(Opcodes.DCONST_0);m.visitInsn(Opcodes.DCONST_0);
        m.visitTypeInsn(Opcodes.NEW,"ye");m.visitInsn(Opcodes.DUP);m.visitVarInsn(Opcodes.ILOAD,3);m.visitInsn(Opcodes.ICONST_1);m.visitInsn(Opcodes.ICONST_0);m.visitMethodInsn(Opcodes.INVOKESPECIAL,"ye","<init>","(III)V",false);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL,"ss","<init>","(Labw;DDDLye;)V",false);m.visitVarInsn(Opcodes.ASTORE,7);
        m.visitVarInsn(Opcodes.ALOAD,7);m.visitIntInsn(Opcodes.BIPUSH,10);m.visitFieldInsn(Opcodes.PUTFIELD,"ss","c","I");
        m.visitIincInsn(6,1);m.visitInsn(Opcodes.RETURN);m.visitMaxs(10,8);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
    private static byte[] unsupportedLuckyLikeClass() {
        ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/UnsupportedLuckyLike",null,"java/lang/Object",null);
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC,"make","(Labw;Lye;)Lss;",null,null);m.visitCode();m.visitTypeInsn(Opcodes.NEW,"ss");m.visitInsn(Opcodes.DUP);m.visitVarInsn(Opcodes.ALOAD,1);m.visitInsn(Opcodes.DCONST_0);m.visitInsn(Opcodes.DCONST_0);m.visitInsn(Opcodes.DCONST_0);m.visitVarInsn(Opcodes.ALOAD,2);m.visitMethodInsn(Opcodes.INVOKESPECIAL,"ss","<init>","(Labw;DDDLye;)V",false);m.visitInsn(Opcodes.ARETURN);m.visitMaxs(9,3);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
    private static void chestBridgeCalls(ClassWriter w,boolean officialNames) {
        String chest=officialNames?"mk":"net/minecraft/util/WeightedRandomChestContent";
        String inventory=officialNames?"mo":"net/minecraft/inventory/IInventory";
        String name=officialNames?"a":"func_76293_a";
        String desc="(Ljava/util/Random;[L"+chest+";L"+inventory+";I)V";
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"chestBridgeCalls","()V",null,null);m.visitCode();
        m.visitInsn(Opcodes.ACONST_NULL);m.visitInsn(Opcodes.ACONST_NULL);m.visitInsn(Opcodes.ACONST_NULL);m.visitInsn(Opcodes.ICONST_0);
        m.visitMethodInsn(Opcodes.INVOKESTATIC,chest,name,desc,false);
        // Near misses must remain untouched: wrong owner and wrong descriptor.
        m.visitInsn(Opcodes.ACONST_NULL);m.visitInsn(Opcodes.ACONST_NULL);m.visitInsn(Opcodes.ACONST_NULL);m.visitInsn(Opcodes.ICONST_0);
        m.visitMethodInsn(Opcodes.INVOKESTATIC,"fixture/NotWeightedRandomChestContent",name,desc,false);
        m.visitMethodInsn(Opcodes.INVOKESTATIC,chest,"func_76293_a","()V",false);
        m.visitInsn(Opcodes.RETURN);m.visitMaxs(4,0);m.visitEnd();
    }
    private static void sandBridgeCalls(ClassWriter w,boolean officialNames) {
        String sand=officialNames?"aos":"net/minecraft/block/BlockSand";
        String world=officialNames?"abw":"net/minecraft/world/World";
        String name=officialNames?"a_":"func_72191_e_";
        String desc="(L"+world+";III)Z";
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"sandBridgeCalls","()V",null,null);m.visitCode();
        m.visitInsn(Opcodes.ACONST_NULL);m.visitInsn(Opcodes.ICONST_0);m.visitInsn(Opcodes.ICONST_0);m.visitInsn(Opcodes.ICONST_0);
        m.visitMethodInsn(Opcodes.INVOKESTATIC,sand,name,desc,false);m.visitInsn(Opcodes.POP);
        // Near misses must remain untouched after remapping: wrong owner and wrong descriptor.
        m.visitInsn(Opcodes.ACONST_NULL);m.visitInsn(Opcodes.ICONST_0);m.visitInsn(Opcodes.ICONST_0);m.visitInsn(Opcodes.ICONST_0);
        m.visitMethodInsn(Opcodes.INVOKESTATIC,"fixture/NotBlockSand",name,desc,false);m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.ACONST_NULL);m.visitInsn(Opcodes.ICONST_0);m.visitInsn(Opcodes.ICONST_0);
        m.visitMethodInsn(Opcodes.INVOKESTATIC,sand,"func_72191_e_","(L"+world+";II)Z",false);m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);m.visitMaxs(4,0);m.visitEnd();
    }
    private static void fieldDescriptorDriftCalls(ClassWriter w,boolean officialNames) {
        String block=officialNames?"aqz":"net/minecraft/block/Block";
        String entity=officialNames?"nn":"net/minecraft/entity/Entity";
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"fieldDescriptorDriftCalls","()V",null,null);m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC,block,officialNames?"cm":"field_82510_ck","L"+block+";");m.visitInsn(Opcodes.POP);
        // Wrong owner, name, incompatible expected type, and access mode must remain untouched.
        m.visitFieldInsn(Opcodes.GETSTATIC,"fixture/NotBlock",officialNames?"cm":"field_82510_ck","L"+block+";");m.visitInsn(Opcodes.POP);
        m.visitFieldInsn(Opcodes.GETSTATIC,block,"wrongCm","L"+block+";");m.visitInsn(Opcodes.POP);
        m.visitFieldInsn(Opcodes.GETSTATIC,block,officialNames?"cm":"field_82510_ck","L"+entity+";");m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.ACONST_NULL);m.visitFieldInsn(Opcodes.GETFIELD,block,officialNames?"cm":"field_82510_ck","L"+block+";");m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);m.visitMaxs(1,0);m.visitEnd();
    }
    private static byte[] gameEntityClass(){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"net/minecraft/entity/Entity",null,"java/lang/Object",null);w.visitField(Opcodes.ACC_PUBLIC,"field_70177_z","F",null,null).visitEnd();w.visitEnd();return w.toByteArray();}
    private static byte[] gameAnvilClass(){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"net/minecraft/block/BlockAnvil",null,"net/minecraft/block/Block",null);w.visitEnd();return w.toByteArray();}
    private static byte[] gameClass(){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"net/minecraft/block/Block",null,"java/lang/Object",null);w.visitField(Opcodes.ACC_PUBLIC,"field_71990_ca","I",null,null).visitEnd();w.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"field_82510_ck","Lnet/minecraft/block/BlockAnvil;",null,null).visitEnd();MethodVisitor i=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);i.visitCode();i.visitVarInsn(Opcodes.ALOAD,0);i.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);i.visitInsn(Opcodes.RETURN);i.visitMaxs(1,1);i.visitEnd();MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC,"func_71857_b","()I",null,null);m.visitCode();m.visitInsn(Opcodes.ICONST_0);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(1,1);m.visitEnd();w.visitEnd();return w.toByteArray();}
    private static byte[] plainClass(){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/Plain",null,"java/lang/Object",null);w.visitEnd();return w.toByteArray();}
    private static Path jar(Path p,Map<String,byte[]> entries,boolean signed)throws Exception{Manifest mf=new Manifest();mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");mf.getMainAttributes().putValue("Fixture-Attr","fixture-value");try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(p),mf)){for(Map.Entry<String,byte[]> e:entries.entrySet()){out.putNextEntry(new JarEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}if(signed){out.putNextEntry(new JarEntry("META-INF/TEST.SF"));out.write("Signature-Version: 1.0\n".getBytes());out.closeEntry();}}return p;}
    private static byte[] read(Path jar,String name)throws Exception{try(JarFile jf=new JarFile(jar.toFile());InputStream in=jf.getInputStream(jf.getJarEntry(name))){return in.readAllBytes();}}
    private static void verifyClass(byte[] b){final boolean[] field={false},method={false},inherited={false},icons={false},iconName={false},harvest={false},drop={false},chestBridge={false},wrongOwner={false},wrongDescriptor={false},sandBridge={false},sandWrongOwner={false},sandWrongDescriptor={false},drift={false},driftWrongOwner={false},driftWrongName={false},driftUnsafe={false},driftWrongOpcode={false};new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9){@Override public void visit(int v,int a,String n,String s,String sup,String[] i){check("net/minecraft/block/Block".equals(sup),"super remapped");}@Override public FieldVisitor visitField(int a,String n,String d,String s,Object v){if(n.equals("block"))field[0]=d.equals("Lnet/minecraft/block/Block;");return null;}@Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){if(n.equals("func_94332_a")&&d.equals("(Lnet/minecraft/client/renderer/texture/IconRegister;)V"))icons[0]=true;if(n.equals("func_71893_a")&&d.equals("(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;IIII)V"))harvest[0]=true;if(n.equals("func_71885_a")&&d.equals("(ILjava/util/Random;I)I"))drop[0]=true;if(n.equals("inheritedField"))return new MethodVisitor(Opcodes.ASM9){@Override public void visitFieldInsn(int op,String o,String fn,String fd){if(fn.equals("field_71990_ca"))inherited[0]=true;}};if(n.equals("func_94332_a"))return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int op,String o,String mn,String md,boolean itf){if(mn.equals("func_71917_a")&&md.equals("()Ljava/lang/String;"))iconName[0]=true;}};if(n.equals("chestBridgeCalls"))return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int op,String o,String mn,String md,boolean itf){String chestDesc="(Ljava/util/Random;[Lnet/minecraft/util/WeightedRandomChestContent;Lnet/minecraft/inventory/IInventory;I)V";if(op==Opcodes.INVOKESTATIC&&o.equals("net/xiaoyu233/fml/modfixer/LegacyForgeChestContentBridge")&&mn.equals("generateChestContents")&&md.equals(chestDesc))chestBridge[0]=true;if(op==Opcodes.INVOKESTATIC&&o.equals("fixture/NotWeightedRandomChestContent")&&mn.equals("func_76293_a")&&md.equals(chestDesc))wrongOwner[0]=true;if(op==Opcodes.INVOKESTATIC&&o.equals("net/minecraft/util/WeightedRandomChestContent")&&mn.equals("func_76293_a")&&md.equals("()V"))wrongDescriptor[0]=true;}};if(n.equals("sandBridgeCalls"))return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int op,String o,String mn,String md,boolean itf){String sandDesc="(Lnet/minecraft/world/World;III)Z";if(op==Opcodes.INVOKESTATIC&&o.equals("net/xiaoyu233/fml/modfixer/LegacyForgeBlockSandBridge")&&mn.equals("canFallBelow")&&md.equals(sandDesc))sandBridge[0]=true;if(op==Opcodes.INVOKESTATIC&&o.equals("fixture/NotBlockSand")&&mn.equals("func_72191_e_")&&md.equals(sandDesc))sandWrongOwner[0]=true;if(op==Opcodes.INVOKESTATIC&&o.equals("net/minecraft/block/BlockSand")&&mn.equals("func_72191_e_")&&md.equals("(Lnet/minecraft/world/World;II)Z"))sandWrongDescriptor[0]=true;}};if(n.equals("fieldDescriptorDriftCalls"))return new MethodVisitor(Opcodes.ASM9){@Override public void visitFieldInsn(int op,String o,String fn,String fd){if(op==Opcodes.GETSTATIC&&o.equals("net/minecraft/block/Block")&&fn.equals("field_82510_ck")&&fd.equals("Lnet/minecraft/block/BlockAnvil;"))drift[0]=true;if(op==Opcodes.GETSTATIC&&o.equals("fixture/NotBlock")&&fn.equals("cm")&&fd.equals("Lnet/minecraft/block/Block;"))driftWrongOwner[0]=true;if(op==Opcodes.GETSTATIC&&o.equals("net/minecraft/block/Block")&&fn.equals("wrongCm")&&fd.equals("Lnet/minecraft/block/Block;"))driftWrongName[0]=true;if(fd.equals("Lnet/minecraft/entity/Entity;")&&op==Opcodes.GETSTATIC&&o.equals("net/minecraft/block/Block")&&fn.equals("cm"))driftUnsafe[0]=true;if(op==Opcodes.GETFIELD&&o.equals("net/minecraft/block/Block")&&fn.equals("cm")&&fd.equals("Lnet/minecraft/block/Block;"))driftWrongOpcode[0]=true;}};if(!n.equals("exercise"))return null;check(d.equals("(Lnet/minecraft/block/Block;)Lnet/minecraft/block/Block;"),"method descriptor");return new MethodVisitor(Opcodes.ASM9){@Override public void visitFieldInsn(int op,String o,String n,String d){if(o.equals("net/minecraft/block/Block")&&n.equals("field_71990_ca"))method[0]=true;}@Override public void visitMethodInsn(int op,String o,String n,String d,boolean itf){if(o.equals("net/minecraft/block/Block")&&n.equals("func_71857_b"))method[0]=true;}};}},0);check(field[0]&&method[0]&&inherited[0],"field/method remapped");check(icons[0]&&iconName[0]&&harvest[0]&&drop[0],"legacy Block overrides remapped");check(chestBridge[0],"legacy chest call rewritten to bridge");check(wrongOwner[0]&&wrongDescriptor[0],"legacy chest near misses retained");check(sandBridge[0],"legacy BlockSand call rewritten to bridge");check(sandWrongOwner[0]&&sandWrongDescriptor[0],"legacy BlockSand near misses retained");check(drift[0],"safe field descriptor drift rewritten");check(driftWrongOwner[0],"field drift wrong owner retained");check(driftWrongName[0],"field drift wrong name retained");check(driftUnsafe[0],"field drift unsafe descriptor retained");check(driftWrongOpcode[0],"field drift unsafe access mode retained");}
    private static void verifyLuckyItemDrop(byte[] b){final boolean[] bridge={false},nullSkip={false},ctor={false};new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9){@Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){if(!n.equals("func_71893_a")||!d.equals("(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;IIII)V"))return null;return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int op,String o,String n,String d,boolean i){if(op==Opcodes.INVOKESTATIC&&o.equals("net/xiaoyu233/fml/modfixer/LegacyLuckyItemDropBridge")&&n.equals("createEntityItem"))bridge[0]=true;if(op==Opcodes.INVOKESPECIAL&&o.equals("net/minecraft/entity/item/EntityItem")&&n.equals("<init>"))ctor[0]=true;}@Override public void visitJumpInsn(int op,Label l){if(op==Opcodes.IFNULL)nullSkip[0]=true;}};}},0);check(bridge[0]&&nullSkip[0]&&!ctor[0],"Lucky invalid/valid item drop site guarded by bridge");}
    private static void verifyUnsupportedLuckyLike(byte[] b){final boolean[] ctor={false},bridge={false};new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9){@Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int op,String o,String n,String d,boolean i){if(op==Opcodes.INVOKESPECIAL&&o.equals("net/minecraft/entity/item/EntityItem")&&n.equals("<init>"))ctor[0]=true;if(o.equals("net/xiaoyu233/fml/modfixer/LegacyLuckyItemDropBridge"))bridge[0]=true;}};}},0);check(ctor[0]&&!bridge[0],"unsupported Lucky-like call retained");}
    private static void verifyAmbiguousFieldRetained(byte[] b){final boolean[] retained={false};new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9){@Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){if(!n.equals("fieldDescriptorDriftCalls"))return null;return new MethodVisitor(Opcodes.ASM9){@Override public void visitFieldInsn(int op,String o,String n,String d){if(op==Opcodes.GETSTATIC&&o.equals("net/minecraft/block/Block")&&n.equals("cm")&&d.equals("Lnet/minecraft/block/Block;"))retained[0]=true;}};}},0);check(retained[0],"ambiguous field candidate retained");}
    private static long countJars(Path gameDir)throws Exception{Path d=gameDir.resolve(".fml/remappedForgeMods");if(!Files.exists(d))return 0;try(var s=Files.list(d)){return s.filter(p->p.toString().endsWith(".jar")).count();}}
    private static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    private static void delete(Path p)throws IOException{if(!Files.exists(p))return;try(var s=Files.walk(p)){s.sorted(Comparator.reverseOrder()).forEach(x->{try{Files.deleteIfExists(x);}catch(IOException e){throw new UncheckedIOException(e);}});}}
}
