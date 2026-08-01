package net.xiaoyu233.fml.modfixer;

import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import net.xiaoyu233.fml.util.Constants;
import org.objectweb.asm.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.*;

/** Pure, fail-closed legacy Forge mod jar remapper. Production discovery is wired separately. */
public final class LegacyForgeModRemapper {
    public enum Namespace { OFFICIAL, INTERMEDIARY, UNKNOWN_NO_GAME_REFS, MIXED }
    public static final class Result {
        public final Namespace namespace;
        public final Path sourcePath, runtimePath;
        public final boolean cacheHit;
        public final int officialReferences, intermediaryReferences;
        Result(Namespace n, Path s, Path r, boolean hit, Scan scan) {
            namespace=n; sourcePath=s; runtimePath=r; cacheHit=hit;
            officialReferences=scan.officialCount; intermediaryReferences=scan.intermediaryCount;
        }
    }

    private static final String RESOURCE="/intermediary.tiny";
    private static final String SCHEMA="legacy-forge-mod-remap-v4-legacy-overrides";
    private static final String TINY_REMAPPER_VERSION="0.14.0";
    private static final Object[] LOCKS=new Object[64];
    static { for (int i=0;i<LOCKS.length;i++) LOCKS[i]=new Object(); }
    private final byte[] mappingBytes;
    private final String schema;
    private final Set<String> official=new HashSet<>(), intermediary=new HashSet<>();
    private final Map<String,String> fieldMappings=new HashMap<>(), methodMappings=new HashMap<>(), intermediaryToOfficial=new HashMap<>(), intermediaryDescriptors=new HashMap<>();
    private final Map<String,String> uniqueFields=new HashMap<>(), uniqueMethods=new HashMap<>();
    private final Map<String,String> gameSuperclasses=new HashMap<>();

    public LegacyForgeModRemapper() throws IOException { this(readResource(), SCHEMA); }
    LegacyForgeModRemapper(byte[] mappings, String schemaVersion) throws IOException {
        mappingBytes=mappings.clone(); schema=schemaVersion; parseMappings();
        if (official.isEmpty()) throw new IOException("No non-identity class mappings in "+RESOURCE);
    }

    public Result prepare(Path sourceJar, Path intermediaryGameJar, Path gameDir) throws IOException {
        sourceJar=sourceJar.toRealPath(); intermediaryGameJar=intermediaryGameJar.toRealPath();
        Scan input=scan(sourceJar);
        if (input.namespace==Namespace.MIXED) throw rejected(sourceJar,input,"mixed namespace");
        if (input.namespace!=Namespace.OFFICIAL) return new Result(input.namespace,sourceJar,sourceJar,false,input);
        String key=hashFiles(sourceJar,intermediaryGameJar);
        String clean=sourceJar.getFileName().toString().replaceAll("[^A-Za-z0-9._-]","_");
        Path dir=gameDir.resolve(".fml/remappedForgeMods"); Files.createDirectories(dir);
        Path output=dir.resolve(clean+"-"+key.substring(0,16)+"-intermediary.jar");
        Object lock=LOCKS[(key.hashCode() & Integer.MAX_VALUE) % LOCKS.length];
        synchronized(lock) {
            if (Files.exists(output)) {
                Scan cached=validateOutput(output);
                return new Result(input.namespace,sourceJar,output,true,input);
            }
            Path tmp=Files.createTempFile(dir,clean+"-", ".tmp");
            try {
                remap(sourceJar,intermediaryGameJar,tmp);
                validateOutput(tmp);
                try { Files.move(tmp,output,StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(tmp,output,StandardCopyOption.REPLACE_EXISTING); }
            } catch (Throwable t) {
                Files.deleteIfExists(tmp);
                if (t instanceof IOException) throw (IOException)t;
                throw new IOException("Failed to remap official Forge mod "+sourceJar.getFileName()+": "+t,t);
            } finally { /* fixed lock stripes require no lifecycle bookkeeping */ }
        }
        return new Result(input.namespace,sourceJar,output,false,input);
    }

    public Namespace scanNamespace(Path jar) throws IOException { return scan(jar).namespace; }

    private net.fabricmc.tinyremapper.IMappingProvider mappingProvider() {
        return acceptor -> {
            Map<String,String> classes=new HashMap<>();
            try (BufferedReader r=new BufferedReader(new InputStreamReader(new ByteArrayInputStream(mappingBytes),StandardCharsets.UTF_8))) {
                String line; while((line=r.readLine())!=null) {String[] p=line.split("\\t");if(p.length>=3&&p[0].equals("CLASS")){classes.put(p[1],p[2]);acceptor.acceptClass(p[1],p[2]);}}
            } catch(IOException e) {throw new UncheckedIOException(e);}
            try (BufferedReader r=new BufferedReader(new InputStreamReader(new ByteArrayInputStream(mappingBytes),StandardCharsets.UTF_8))) {
                String line; while((line=r.readLine())!=null) {String[] p=line.split("\\t");if(p.length<5)continue;String desc=mapDescriptor(p[2],classes);if(p[0].equals("FIELD"))acceptor.acceptField(new net.fabricmc.tinyremapper.IMappingProvider.Member(p[1],p[3],desc),p[4]);else if(p[0].equals("METHOD"))acceptor.acceptMethod(new net.fabricmc.tinyremapper.IMappingProvider.Member(p[1],p[3],desc),p[4]);}
            } catch(IOException e) {throw new UncheckedIOException(e);}
        };
    }
    private static String mapDescriptor(String desc,Map<String,String> classes) {StringBuilder out=new StringBuilder();for(int i=0;i<desc.length();){if(desc.charAt(i)=='L'){int end=desc.indexOf(';',i);if(end<0)return desc;String n=desc.substring(i+1,end);out.append('L').append(classes.getOrDefault(n,n)).append(';');i=end+1;}else out.append(desc.charAt(i++));}return out.toString();}

    private void remap(Path source, Path game, Path output) throws IOException {
        loadGameHierarchy(game);
        TinyRemapper remapper=TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(mappingBytes),StandardCharsets.UTF_8)),"official","intermediary"))
                .extraRemapper(new org.objectweb.asm.commons.Remapper() {
                    @Override public String mapFieldName(String owner,String name,String descriptor) { return memberName(fieldMappings,uniqueFields,owner,name,descriptor); }
                    @Override public String mapMethodName(String owner,String name,String descriptor) { return memberName(methodMappings,uniqueMethods,owner,name,descriptor); }
                })
                .ignoreFieldDesc(true).ignoreConflicts(false).checkPackageAccess(false).build();
        Path classes=Files.createTempDirectory(output.getParent(), "forge-remap-classes-");
        try (OutputConsumerPath consumer=new OutputConsumerPath.Builder(classes).build()) {
            remapper.readClassPath(game); remapper.readInputs(source); remapper.apply((className,bytes) -> consumer.accept(className, fixUnmappedMembers(bytes)));
            packageOutput(source, classes, output);
        } finally { remapper.finish(); deleteTree(classes); }
    }

    private byte[] fixUnmappedMembers(byte[] bytes) {
        ClassReader reader=new ClassReader(bytes);
        // Include the current remapped mod class in the hierarchy before member
        // instructions are visited. Published bytecode commonly uses itself as
        // owner for inherited fields (EntityFallingBlock.d -> EntityFallingSand).
        if(reader.getSuperName()!=null)gameSuperclasses.put(reader.getClassName(),reader.getSuperName());
        ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        ClassVisitor miteAdapters=new ClassVisitor(Opcodes.ASM9,writer) {
            private boolean directBlockSubclass;
            @Override public void visit(int version,int access,String name,String signature,String superName,String[] interfaces) {
                directBlockSubclass="net/minecraft/block/Block".equals(superName);
                super.visit(version,access,name,signature,superName,interfaces);
            }
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                // TinyRemapper cannot resolve inherited names when a published mod
                // subclass is absent from the game classpath hierarchy. Adapt the
                // three legacy Block overrides used by the real icon/harvest path.
                if(directBlockSubclass&&name.equals("a")) {
                    if(descriptor.equals("(Lnet/minecraft/client/renderer/texture/IconRegister;)V"))name="func_94332_a";
                    else if(descriptor.equals("(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;IIII)V"))name="func_71893_a";
                    else if(descriptor.equals("(ILjava/util/Random;I)I"))name="func_71885_a";
                }
                MethodVisitor next=super.visitMethod(access,name,descriptor,signature,exceptions);
                return new MethodVisitor(Opcodes.ASM9,next) {
                    @Override public void visitMethodInsn(int opcode,String owner,String methodName,String methodDescriptor,boolean isInterface) {
                        // Self-owned calls in a mod subclass have no mapping owner.
                        // Resolve the exact inherited Block members used by legacy
                        // icon registration before emitting the remapped class.
                        if(directBlockSubclass&&opcode==Opcodes.INVOKEVIRTUAL&&methodName.equals("a")&&methodDescriptor.equals("()Ljava/lang/String;")) {
                            methodName="func_71917_a";
                        }
                        if(opcode==Opcodes.INVOKESPECIAL && owner.equals("net/minecraft/block/Block") && methodName.equals("<init>") && methodDescriptor.equals("(ILnet/minecraft/block/material/Material;)V")) {
                            super.visitTypeInsn(Opcodes.NEW,"net/minecraft/block/BlockConstants");
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESPECIAL,"net/minecraft/block/BlockConstants","<init>","()V",false);
                            methodDescriptor="(ILnet/minecraft/block/material/Material;Lnet/minecraft/block/BlockConstants;)V";
                        }
                        super.visitMethodInsn(opcode,owner,methodName,methodDescriptor,isInterface);
                    }
                };
            }
        };
        reader.accept(new org.objectweb.asm.commons.ClassRemapper(miteAdapters,new org.objectweb.asm.commons.Remapper(){
            @Override public String mapFieldName(String owner,String name,String descriptor){return memberName(fieldMappings,uniqueFields,owner,name,descriptor);}
            @Override public String mapMethodName(String owner,String name,String descriptor){return memberName(methodMappings,uniqueMethods,owner,name,descriptor);}
        }),0); return writer.toByteArray();
    }

    private static void packageOutput(Path source, Path classes, Path output) throws IOException {
        try (JarFile input=new JarFile(source.toFile())) {
            Manifest manifest=input.getManifest();
            if (manifest==null) manifest=new Manifest();
            manifest.getMainAttributes().putIfAbsent(Attributes.Name.MANIFEST_VERSION,"1.0");
            manifest.getEntries().values().forEach(a -> a.keySet().removeIf(k -> k.toString().toUpperCase(Locale.ROOT).contains("DIGEST")));
            try (JarOutputStream out=new JarOutputStream(Files.newOutputStream(output),manifest)) {
                Enumeration<JarEntry> entries=input.entries();
                while(entries.hasMoreElements()) {
                    JarEntry e=entries.nextElement(); String upper=e.getName().toUpperCase(Locale.ROOT);
                    if(e.isDirectory() || e.getName().equalsIgnoreCase("META-INF/MANIFEST.MF") || e.getName().endsWith(".class") ||
                            upper.startsWith("META-INF/") && (upper.endsWith(".SF")||upper.endsWith(".RSA")||upper.endsWith(".DSA")||upper.substring(9).startsWith("SIG-"))) continue;
                    out.putNextEntry(new JarEntry(e.getName())); try(InputStream in=input.getInputStream(e)){in.transferTo(out);} out.closeEntry();
                }
                try(java.util.stream.Stream<Path> stream=Files.walk(classes)) {
                    for(Path p:(Iterable<Path>)stream.filter(Files::isRegularFile)::iterator) {
                        String name=classes.relativize(p).toString().replace(File.separatorChar,'/');
                        out.putNextEntry(new JarEntry(name)); Files.copy(p,out); out.closeEntry();
                    }
                }
            }
        }
    }
    private static void deleteTree(Path root) throws IOException { if(root==null||!Files.exists(root))return;try(java.util.stream.Stream<Path> s=Files.walk(root)){for(Path p:(Iterable<Path>)s.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}

    private Scan validateOutput(Path jar) throws IOException {
        Scan s=scan(jar);
        if (s.classCount==0) throw new IOException("Remap output has no classes: "+jar);
        if (s.namespace==Namespace.OFFICIAL || s.namespace==Namespace.MIXED)
            throw rejected(jar,s,"output still contains official game references");
        try (JarFile jf=new JarFile(jar.toFile())) {
            if (jf.getManifest()==null) throw new IOException("Remap output manifest is missing: "+jar);
            Enumeration<JarEntry> e=jf.entries();
            while(e.hasMoreElements()) {
                String n=e.nextElement().getName().toUpperCase(Locale.ROOT);
                if (n.startsWith("META-INF/") && (n.endsWith(".SF")||n.endsWith(".RSA")||n.endsWith(".DSA")||n.substring(9).startsWith("SIG-")))
                    throw new IOException("Remap output retained invalid signature metadata: "+n);
            }
        }
        return s;
    }

    private Scan scan(Path jar) throws IOException {
        Scan out=new Scan();
        try (JarFile jf=new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries=jf.entries();
            while(entries.hasMoreElements()) {
                JarEntry e=entries.nextElement(); if (e.isDirectory()||!e.getName().endsWith(".class")) continue;
                out.classCount++;
                try (InputStream in=jf.getInputStream(e)) { new ClassReader(in).accept(new RefVisitor(out),ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES); }
                catch (RuntimeException ex) { throw new IOException("Cannot scan class "+e.getName()+" in "+jar,ex); }
            }
        }
        out.finish(); return out;
    }

    private final class RefVisitor extends ClassVisitor {
        private final Scan s;
        RefVisitor(Scan s){super(Opcodes.ASM9);this.s=s;}
        void type(String d){ if(d==null)return; try { Type t=d.charAt(0)=='('?Type.getMethodType(d):Type.getType(d); walk(t); } catch(IllegalArgumentException ignored){} }
        void walk(Type t){ if(t.getSort()==Type.ARRAY)walk(t.getElementType()); else if(t.getSort()==Type.METHOD){walk(t.getReturnType());for(Type a:t.getArgumentTypes())walk(a);} else if(t.getSort()==Type.OBJECT) name(t.getInternalName()); }
        void name(String n){if(n==null)return;if(official.contains(n)){s.officialCount++;s.evidence.add(n);}if(intermediary.contains(n)){s.intermediaryCount++;s.evidence.add(n);}}
        @Override public void visit(int v,int a,String n,String sig,String sup,String[] ints){name(sup);if(ints!=null)for(String i:ints)name(i);signature(sig);}
        void signature(String sig){if(sig!=null)new org.objectweb.asm.signature.SignatureReader(sig).accept(new org.objectweb.asm.signature.SignatureVisitor(Opcodes.ASM9){@Override public void visitClassType(String n){name(n);}});}
        @Override public FieldVisitor visitField(int a,String n,String d,String sig,Object val){type(d);signature(sig);constant(val);return new FieldVisitor(Opcodes.ASM9){@Override public AnnotationVisitor visitAnnotation(String d,boolean vis){type(d);return annotation();}};}
        @Override public MethodVisitor visitMethod(int a,String n,String d,String sig,String[] ex){type(d);signature(sig);if(ex!=null)for(String x:ex)name(x);return new MethodVisitor(Opcodes.ASM9){
            @Override public void visitTypeInsn(int op,String t){name(t);} @Override public void visitFieldInsn(int op,String o,String n,String d){name(o);type(d);} @Override public void visitMethodInsn(int op,String o,String n,String d,boolean itf){name(o);type(d);} @Override public void visitLdcInsn(Object v){constant(v);} @Override public void visitMultiANewArrayInsn(String d,int x){type(d);} @Override public void visitInvokeDynamicInsn(String n,String d,Handle b,Object... args){type(d);constant(b);for(Object x:args)constant(x);} @Override public AnnotationVisitor visitAnnotation(String d,boolean vis){type(d);return annotation();}
        };}
        void constant(Object v){if(v instanceof Type)walk((Type)v);else if(v instanceof Handle){Handle h=(Handle)v;name(h.getOwner());type(h.getDesc());}else if(v instanceof ConstantDynamic){ConstantDynamic c=(ConstantDynamic)v;type(c.getDescriptor());constant(c.getBootstrapMethod());for(int i=0;i<c.getBootstrapMethodArgumentCount();i++)constant(c.getBootstrapMethodArgument(i));}}
        AnnotationVisitor annotation(){return new AnnotationVisitor(Opcodes.ASM9){@Override public void visit(String n,Object v){constant(v);}@Override public void visitEnum(String n,String d,String v){type(d);}@Override public AnnotationVisitor visitAnnotation(String n,String d){type(d);return this;}@Override public AnnotationVisitor visitArray(String n){return this;}};}
    }

    private static final class Scan {int officialCount,intermediaryCount,classCount;Namespace namespace;final LinkedHashSet<String> evidence=new LinkedHashSet<>();void finish(){namespace=officialCount>0&&intermediaryCount>0?Namespace.MIXED:officialCount>0?Namespace.OFFICIAL:intermediaryCount>0?Namespace.INTERMEDIARY:Namespace.UNKNOWN_NO_GAME_REFS;}}
    private IOException rejected(Path p,Scan s,String why){return new IOException("Rejected Forge mod "+p.getFileName()+" namespace="+s.namespace+", first references="+s.evidence+": "+why);}
    private String toOfficialDescriptor(String desc) { org.objectweb.asm.commons.Remapper r=new org.objectweb.asm.commons.Remapper(){@Override public String map(String name){return intermediaryToOfficial.getOrDefault(name,name);}}; return desc.startsWith("(")?r.mapMethodDesc(desc):r.mapDesc(desc); }
    private String memberName(Map<String,String> exact,Map<String,String> unique,String owner,String name,String desc) {
        String mapped=exact.get(owner+'\u0000'+name+'\u0000'+desc);
        String officialOwner=intermediaryToOfficial.get(owner);
        String officialDesc=toOfficialDescriptor(desc);
        if(mapped==null&&officialOwner!=null)mapped=exact.get(officialOwner+'\u0000'+name+'\u0000'+officialDesc);
        // Mod subclasses are not mapping owners, so inherited overrides need a
        // descriptor-normalized unique fallback.  The old intermediary-only lookup
        // left methods such as Block.registerIcons as a(IconRegister), silently
        // breaking virtual dispatch.
        if(mapped==null)mapped=unique.get(name+'\u0000'+desc);
        if(mapped==null&&!officialDesc.equals(desc))mapped=unique.get(name+'\u0000'+officialDesc);
        // Bytecode may retain a subclass as owner for an inherited member. Walk
        // the real intermediary MITE hierarchy and query exact official mappings
        // instead of guessing by a globally common obfuscated name such as A/F.
        for(String parent=gameSuperclasses.get(owner);mapped==null&&parent!=null;parent=gameSuperclasses.get(parent)){
            String officialParent=intermediaryToOfficial.get(parent);
            if(officialParent!=null)mapped=exact.get(officialParent+'\u0000'+name+'\u0000'+officialDesc);
        }
        return mapped==null?name:mapped;
    }
    private void loadGameHierarchy(Path game) throws IOException {
        gameSuperclasses.clear();
        try(JarFile jar=new JarFile(game.toFile())) {
            Enumeration<JarEntry> entries=jar.entries();
            while(entries.hasMoreElements()) {
                JarEntry entry=entries.nextElement();
                if(entry.isDirectory()||!entry.getName().endsWith(".class"))continue;
                try(InputStream in=jar.getInputStream(entry)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9){
                        @Override public void visit(int v,int a,String n,String s,String parent,String[] i){if(parent!=null)gameSuperclasses.put(n,parent);}
                    },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
                }
            }
        }
    }

    private void parseMappings() throws IOException {
        Map<String,String> fieldCandidates=new HashMap<>(), methodCandidates=new HashMap<>(); Set<String> ambiguousFields=new HashSet<>(), ambiguousMethods=new HashSet<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new ByteArrayInputStream(mappingBytes),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null){String[] p=line.split("\\t");
            if(p.length>=3&&p[0].equals("CLASS")&&!p[1].equals(p[2])){official.add(p[1]);intermediary.add(p[2]);intermediaryToOfficial.put(p[2],p[1]);}
            else if(p.length>=5&&(p[0].equals("FIELD")||p[0].equals("METHOD"))&&!p[3].equals(p[4])){Map<String,String> exact=p[0].equals("FIELD")?fieldMappings:methodMappings;Map<String,String> candidates=p[0].equals("FIELD")?fieldCandidates:methodCandidates;Set<String> ambiguous=p[0].equals("FIELD")?ambiguousFields:ambiguousMethods;exact.put(p[1]+'\u0000'+p[3]+'\u0000'+p[2],p[4]);String key=p[3]+'\u0000'+p[2],old=candidates.putIfAbsent(key,p[4]);if(old!=null&&!old.equals(p[4]))ambiguous.add(key);}
        }} fieldCandidates.keySet().removeAll(ambiguousFields);methodCandidates.keySet().removeAll(ambiguousMethods);uniqueFields.putAll(fieldCandidates);uniqueMethods.putAll(methodCandidates);for(Map.Entry<String,String> e:intermediaryToOfficial.entrySet())intermediaryDescriptors.put("L"+e.getKey()+";","L"+e.getValue()+";");
    }
    private String hashFiles(Path source,Path game) throws IOException {try{MessageDigest d=MessageDigest.getInstance("SHA-256");update(d,source);d.update(mappingBytes);update(d,game);d.update(Constants.VERSION.getBytes(StandardCharsets.UTF_8));d.update(schema.getBytes(StandardCharsets.UTF_8));d.update(TINY_REMAPPER_VERSION.getBytes(StandardCharsets.UTF_8));return hex(d.digest());}catch(Exception e){throw new IOException("Cannot create Forge mod remap cache key",e);}}
    private static void update(MessageDigest d,Path p)throws IOException{try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[8192];for(int n;(n=in.read(b))>=0;)d.update(b,0,n);}}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    private static byte[] readResource() throws IOException {try(InputStream in=LegacyForgeModRemapper.class.getResourceAsStream(RESOURCE)){if(in==null)throw new IOException("Missing "+RESOURCE);return in.readAllBytes();}}
}
