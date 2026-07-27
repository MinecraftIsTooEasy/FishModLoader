package net.xiaoyu233.fml;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.gson.JsonElement;
import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.impl.LazyMappingResolver;
import net.fabricmc.loader.impl.MappingResolverImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.*;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.metadata.*;
import net.fabricmc.loader.impl.util.DefaultLanguageAdapter;
import net.fabricmc.loader.impl.util.ExceptionUtil;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.mappingio.tree.MappingTree;
import net.xiaoyu233.fml.config.ConfigRegistry;
import net.xiaoyu233.fml.config.Configs;
import net.xiaoyu233.fml.config.InjectionConfig;
import net.xiaoyu233.fml.relaunch.Launch;
import net.xiaoyu233.fml.reload.transform.MinecraftServerTrans;
import net.xiaoyu233.fml.util.Constants;
import net.xiaoyu233.fml.util.MITEReleaseAccess;
import net.xiaoyu233.fml.util.RemoteModInfo;
import net.xiaoyu233.fml.util.UrlUtil;
import net.fabricmc.loader.impl.util.mappings.MixinIntermediaryDevRemapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.service.MixinService;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class FishModLoader {
   public static final File CONFIG_DIR = new File("config");
   public static final String VERSION = Constants.VERSION;
   private static final String MOD_ID = Constants.MOD_ID;
   public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
   public static final File MOD_DIR = new File("mods");
   private static final Map<String, ModContainerImpl> modsMapForLoginCheck  = new HashMap<>();
   private static final ArrayList<ModContainerImpl> mods = new ArrayList<>();
   private static final Map<String, ModContainerImpl> modsMap = new HashMap<>();
   private static boolean isServer = false;
   //Cancel version check
   private static final List<ConfigRegistry> ALL_REGISTRIES = new ArrayList<>();
   public static final ConfigRegistry CONFIG_REGISTRY = new ConfigRegistry(Configs.CONFIG, Configs.CONFIG_FILE);
   private static final Map<String, LanguageAdapter> adapterMap = new HashMap<>();
   private static final EntrypointStorage entrypointStorage = new EntrypointStorage();
   private static final AccessWidener accessWidener = new AccessWidener();
   private static boolean frozen;
   private static final boolean IS_DEVELOPMENT = Boolean.parseBoolean(System.getProperty(SystemProperties.DEVELOPMENT, "false"));
   private MappingResolver mappingResolver;
   private static MappingConfiguration mappingConfiguration = new MappingConfiguration();
   private static final List<Path> classPath = new ArrayList<>();

   static {
      try {
         UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
      } catch (Exception ignored) {
      }
   }
   private static Path gameJarPath;

   public static Path getGameJarPath() {
      return gameJarPath;
   }

   public static void addConfigRegistry(ConfigRegistry configRegistry){
      if (!ALL_REGISTRIES.contains(configRegistry)){
         ALL_REGISTRIES.add(configRegistry);
      }
   }

   public static List<ConfigRegistry> getAllConfigRegistries() {
       return ALL_REGISTRIES;
   }

   public static void reloadAllConfigs(){
      for (ConfigRegistry configRegistry : ALL_REGISTRIES) {
         configRegistry.reloadConfig();
      }
   }

   public static EnvType getEnvironmentType() {
      return isServer ? EnvType.SERVER : EnvType.CLIENT;
   }

   private FishModLoader() {
      classPath.clear();
      
      List<String> missing = null;
      List<String> unsupported = null;

      for (String cpEntry : System.getProperty("java.class.path").split(File.pathSeparator)) {
         if (cpEntry.equals("*") || cpEntry.endsWith(File.separator + "*")) {
            if (unsupported == null) unsupported = new ArrayList<>();
            unsupported.add(cpEntry);
            continue;
         }
         
         Path path = Paths.get(cpEntry);
         
         if (!Files.exists(path)) {
            if (missing == null) missing = new ArrayList<>();
            missing.add(cpEntry);
            continue;
         }
         
         classPath.add(LoaderUtil.normalizeExistingPath(path));
      }
   }
   
   public static List<Path> getClassPath() {
      return classPath;
   }

   public static Optional<ModContainer> getModContainer(String parentModId) {
      return Optional.ofNullable(modsMap.get(parentModId));
   }

   public static void freeze() {
      if (frozen) {
         throw new IllegalStateException("Already frozen!");
      }

      frozen = true;
      finishModLoading();
   }

   private static void finishModLoading() {
      // add mods to classpath
      // TODO: This can probably be made safer, but that's a long-term goal
      for (ModContainerImpl mod : mods) {
         if (!mod.getMetadata().getId().equals(MOD_ID) && !mod.getMetadata().getType().equals("builtin")) {
            for (Path path : mod.getCodeSourcePaths()) {
               Launch.knotLoader.addCodeSource(path);
            }
         }
      }

      // Forge mod discovery: scan mods/ for jars with mcmod.info / @Mod / forge_at.cfg.
      // Adds them to the class loader, applies their AT to FML's AccessWidener,
      // and queues their @Mod classes for the lifecycle dispatcher (stage 4).
      //
      // Must go through the KnotClassLoader copy: net.xiaoyu233.fml.modfixer is
      // whitelisted out of the "block" list (LaunchClassBlocker) because it has to
      // see game classes, so it exists twice -- once per loader. A static call from
      // here would fill the AppClassLoader copy's discovered list while
      // LegacyModLifecycle.constructAll reads the Knot one, and no mod would ever
      // be constructed.
      invokeInKnot("net.xiaoyu233.fml.modfixer.ForgeModDiscoverer", "discoverIn", MOD_DIR.toPath());

      // NOTE: Forge mod CONSTRUCTION (LegacyModLifecycle.constructAll) is intentionally
      // deferred until after the mixin platform has been injected — see Launch.java.
      // Constructing a Forge @Mod via Class.forName(initialize=true) triggers linkage
      // of its superclass / field types / method signature types, which often pull in
      // vanilla classes (EntityPlayer, World, Entity, …). If that happens before mixins
      // are wired up, those classes load "too early" and critical fix mixins
      // (FixBoundingBoxCheck, id_extend.WorldMixin, EntityHumanTrans, …) cannot apply.
      // Without FixBoundingBoxCheck the server keeps yanking the player back into the
      // last "valid" position every tick, which looks exactly like the player is stuck.

      setupLanguageAdapters();
      setupMods();
   }

   /**
    * Construct discovered Forge mod instances and emit FMLConstructionEvent.
    * MUST be called AFTER the mixin platform has been initialized and injected,
    * otherwise classes referenced by Forge @Mod classes will be loaded before
    * mixins can attach to them. The classic 3-phase init (PreInit/Init/PostInit)
    * is fired separately by {@link #fireForgePreInit()} etc. once Minecraft itself
    * reaches each phase.
    */
   public static void constructForgeMods() {
      // Go through the KnotClassLoader copy of LegacyModLifecycle, the same one
      // the fireForge* hooks reach. Calling it statically from here binds the
      // AppClassLoader copy instead, so construction and dispatch would land on
      // two different classes with two separate loadedMods lists -- mods get
      // constructed but never receive a single lifecycle event.
      if (!invokeForgeLifecycle("constructAll")) {
         LOGGER.error("Forge mod construction did not run; Forge mods will receive no lifecycle events");
      }
   }

   private static void setupMods() {
      for (ModContainerImpl mod : mods) {
         try {
            for (String in : mod.getInfo().getOldInitializers()) {
               String adapter = mod.getInfo().getOldStyleLanguageAdapter();
               entrypointStorage.addDeprecated(mod, adapter, in);
            }

            for (String key : mod.getInfo().getEntrypointKeys()) {
               for (EntrypointMetadata in : mod.getInfo().getEntrypoints(key)) {
                  entrypointStorage.add(mod, key, in, adapterMap);
               }
            }
            if (mod.getMetadata().getEnvironment().matches(EnvType.CLIENT)) {
               modsMapForLoginCheck.put(mod.getMetadata().getId(), mod);
            }
         } catch (Exception e) {
            throw new RuntimeException(String.format("Failed to setup mod %s (%s)", mod.getInfo().getName(), mod.getOrigin()), e);
         }
      }

   }

   private static void setupLanguageAdapters() {
      adapterMap.put("default", DefaultLanguageAdapter.INSTANCE);

      for (ModContainerImpl mod : mods) {
         // add language adapters
         for (Map.Entry<String, String> laEntry : mod.getInfo().getLanguageAdapterDefinitions().entrySet()) {
            if (adapterMap.containsKey(laEntry.getKey())) {
               throw new RuntimeException("Duplicate language adapter key: " + laEntry.getKey() + "! (" + laEntry.getValue() + ", " + adapterMap.get(laEntry.getKey()).getClass().getName() + ")");
            }

            try {
               adapterMap.put(laEntry.getKey(), (LanguageAdapter) Class.forName(laEntry.getValue(), true, Launch.knotLoader.getClassLoader()).getDeclaredConstructor().newInstance());
            } catch (Exception e) {
               throw new RuntimeException("Failed to instantiate language adapter: " + laEntry.getKey(), e);
            }
         }
      }
   }

   private static void addMod(ModCandidate candidate) {
      ModContainerImpl container = new ModContainerImpl(candidate);
      mods.add(container);
      modsMap.put(candidate.getId(), container);

      for (String provides : candidate.getProvides()) {
         modsMap.put(provides, container);
      }
   }

   private static void dumpModList(List<ModCandidate> mods) {
      StringBuilder modListText = new StringBuilder();

      boolean[] lastItemOfNestLevel = new boolean[mods.size()];
      List<ModCandidate> topLevelMods = mods.stream()
              .filter(mod -> mod.getParentMods().isEmpty())
              .toList();
      int topLevelModsCount = topLevelMods.size();

      for (int i = 0; i < topLevelModsCount; i++) {
         boolean lastItem = i == topLevelModsCount - 1;

         if (lastItem) lastItemOfNestLevel[0] = true;

         dumpModList0(topLevelMods.get(i), modListText, 0, lastItemOfNestLevel);
      }

      int modsCount = mods.size();
      LOGGER.info( "Loading {} mod{}: \n{}", modsCount, modsCount != 1 ? "s" : "", modListText);
   }

   private static void dumpModList0(ModCandidate mod, StringBuilder log, int nestLevel, boolean[] lastItemOfNestLevel) {
      if (log.length() > 0) log.append('\n');

      for (int depth = 0; depth < nestLevel; depth++) {
         log.append(depth == 0 ? "\t" : lastItemOfNestLevel[depth] ? "     " : "   | ");
      }

      log.append(nestLevel == 0 ? "\t" : "  ");
      log.append(nestLevel == 0 ? "-" : lastItemOfNestLevel[nestLevel] ? " \\--" : " |--");
      log.append(' ');
      log.append(mod.getId());
      log.append(' ');
      log.append(mod.getVersion().getFriendlyString());

      List<ModCandidate> nestedMods = new ArrayList<>(mod.getNestedMods());
      nestedMods.sort(Comparator.comparing(nestedMod -> nestedMod.getMetadata().getId()));

      if (!nestedMods.isEmpty()) {
         Iterator<ModCandidate> iterator = nestedMods.iterator();
         ModCandidate nestedMod;
         boolean lastItem;

         while (iterator.hasNext()) {
            nestedMod = iterator.next();
            lastItem = !iterator.hasNext();

            if (lastItem) lastItemOfNestLevel[nestLevel+1] = true;

            dumpModList0(nestedMod, log, nestLevel + 1, lastItemOfNestLevel);

            if (lastItem) lastItemOfNestLevel[nestLevel+1] = false;
         }
      }
   }

   public static void initModMixin() {
      System.setProperty("mixin.bootstrapService", net.xiaoyu233.fml.mixin.service.MixinServiceBootstrap.class.getName());
      System.setProperty("mixin.service", net.xiaoyu233.fml.mixin.service.MixinService.class.getName());

      MixinBootstrap.init();
      
      if (FishModLoader.isDevelopmentEnvironment()) {
         MappingConfiguration config = FishModLoader.getMappingConfiguration();
         MappingTree mappings = config.getMappings();
         final String modNs = config.getDefaultModDistributionNamespace();
         String runtimeNs = config.getRuntimeNamespace();
         
         if (config.hasAnyMappings() && !modNs.equals(runtimeNs)) {
            List<String> namespaces = new ArrayList<>(mappings.getDstNamespaces());
            namespaces.add(mappings.getSrcNamespace());
            
            if (namespaces.contains(modNs) && namespaces.contains(runtimeNs)) {
               System.setProperty("mixin.env.remapRefMap", "true");
               
               try {
                  MixinIntermediaryDevRemapper remapper = new MixinIntermediaryDevRemapper(mappings, modNs, runtimeNs);
                  MixinEnvironment.getDefaultEnvironment().getRemappers().add(remapper);
                  Log.info(LogCategory.MIXIN, "Loaded Fabric development mappings for mixin remapper!");
               } catch (Exception e) {
                  Log.error(LogCategory.MIXIN, "Fabric development environment setup error - the game will probably crash soon!", e);
               }
            }
         }
      }

      registerModloaderMixin(Launch.knotLoader.getClassLoader());
      Map<String, ModContainerImpl> configToModMap = new HashMap<>();

      for (ModContainerImpl mod : mods) {
         for (String config : mod.getMetadata().getMixinConfigs(getEnvironmentType())) {
            ModContainerImpl prev = configToModMap.putIfAbsent(config, mod);
            if (prev != null) throw new RuntimeException(String.format("Non-unique Mixin config name %s used by the mods %s and %s", config, prev.getMetadata().getId(), mod.getMetadata().getId()));

            try {
               Mixins.addConfiguration(config);
            } catch (Throwable t) {
               throw new RuntimeException(String.format("Error creating Mixin config %s for mod %s", config, mod.getMetadata().getId()), t);
            }
         }
      }

      for (Config config : Mixins.getConfigs()) {
         ModContainerImpl mod = configToModMap.get(config.getName());
         if (mod == null) continue;
      }

      finishMixinBootstrapping();
   }

   public static boolean hasEntrypoints(String key) {
      return entrypointStorage.hasEntrypoints(key);
   }

   public static <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
      if (!hasEntrypoints(key)) {
         Log.debug(LogCategory.ENTRYPOINT, "No subscribers for entrypoint '%s'", key);
         return;
      }

      RuntimeException exception = null;
      Collection<EntrypointContainer<T>> entrypoints = getEntrypointContainers(key, type);

      Log.debug(LogCategory.ENTRYPOINT, "Iterating over entrypoint '%s'", key);

      for (EntrypointContainer<T> container : entrypoints) {
         try {
            invoker.accept(container.getEntrypoint());
         } catch (Throwable t) {
            exception = ExceptionUtil.gatherExceptions(t,
                    exception,
                    exc -> new RuntimeException(String.format("Could not execute entrypoint stage '%s' due to errors, provided by '%s'!",
                            key, container.getProvider().getMetadata().getId()),
                            exc));
         }
      }

      if (exception != null) {
         throw exception;
      }
   }

   public MappingResolver getMappingResolver() {
      if (mappingResolver == null) {
         MappingConfiguration config = FishModLoader.getMappingConfiguration();
         String runtimeNamespace = config.getRuntimeNamespace();
         
         mappingResolver = new LazyMappingResolver(() -> new MappingResolverImpl(config.getMappings(), runtimeNamespace),
                 runtimeNamespace);
      }
      
      return mappingResolver;
   }
   
   public static MappingConfiguration getMappingConfiguration() {
      return mappingConfiguration;
   }
   
   public static <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
      return entrypointStorage.getEntrypointContainers(key, type);
   }

   private static void finishMixinBootstrapping() {
      try {
         Method m = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
         m.setAccessible(true);
         m.invoke(null, MixinEnvironment.Phase.INIT);
         m.invoke(null, MixinEnvironment.Phase.DEFAULT);
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   public static AccessWidener getAccessWidener() {
      return accessWidener;
   }

   public static void loadAccessWideners() {
      AccessWidenerReader accessWidenerReader = new AccessWidenerReader(accessWidener);

      for (net.fabricmc.loader.api.ModContainer modContainer : mods) {
         LoaderModMetadata modMetadata = (LoaderModMetadata) modContainer.getMetadata();
         String accessWidener = modMetadata.getAccessWidener();
         if (accessWidener == null) continue;

         Path path = modContainer.findPath(accessWidener).orElse(null);
         if (path == null) throw new RuntimeException(String.format("Missing accessWidener file %s from mod %s", accessWidener, modContainer.getMetadata().getId()));

         try (BufferedReader reader = Files.newBufferedReader(path)) {
            // The runtime game jar is in the *intermediary* namespace, and the
            // build remaps fishmodloader.accesswidener to match (see
            // RemapAccessWidener). AccessWidener matches members by name, so
            // reading a namespace that disagrees with the jar widens nothing and
            // MITE's cross-package accesses fail with IllegalAccessError.
            accessWidenerReader.read(reader, "intermediary");
         } catch (Exception e) {
            throw new RuntimeException("Failed to read accessWidener file from mod " + modMetadata.getId(), e);
         }
      }
   }

   public static ImmutableMap<String, ModContainerImpl> getModsMap() {
      return new ImmutableMap.Builder<String, ModContainerImpl>().putAll(modsMap).build();
   }

   public static JsonElement getModsJson() {
      return RemoteModInfo.writeToJson(mods.stream().map(ModContainerImpl::getMetadata).map(RemoteModInfo::new).collect(Collectors.toList()));
   }


   public static Map<String, ModContainerImpl> getModsMapForLoginCheck() {
      return new HashMap<>(modsMapForLoginCheck);
   }

   public static boolean hasMod(String modid){
      return modsMap.containsKey(modid);
   }

   public static boolean isServer() {
      return isServer;
   }

   public static void registerModloaderMixin(ClassLoader classLoader){
      Mixins.registerConfiguration((InjectionConfig.Builder.of(MOD_ID, MinecraftServerTrans.class.getPackage(), MixinEnvironment.Phase.DEFAULT).build().toConfig(classLoader, MixinService.getService(),MixinEnvironment.getCurrentEnvironment())));
   }

   /** Forge mod lifecycle: classic 3-phase init. Called by Minecraft startup hooks (stage 5). */
   @Deprecated
   public static void fireForgePreInit()  { invokeForgeLifecycle("firePreInit"); }
   public static void fireForgeInit()     { invokeForgeLifecycle("fireInit"); }
   public static void fireForgePostInit() { invokeForgeLifecycle("firePostInit"); }
   public static void fireForgeServerAboutToStart(Object server) { invokeForgeLifecycle("fireServerAboutToStart", server); }
   public static void fireForgeServerStarting(Object server) { invokeForgeLifecycle("fireServerStarting", server); }
   public static void fireForgeServerStarted()  { invokeForgeLifecycle("fireServerStarted"); }
   public static void fireForgeServerStopping() { invokeForgeLifecycle("fireServerStopping"); }
   public static void fireForgeServerStopped()  { invokeForgeLifecycle("fireServerStopped"); }

   private static boolean invokeForgeLifecycle(String methodName, Object... args) {
      return invokeInKnot("net.xiaoyu233.fml.modfixer.LegacyModLifecycle", methodName, args);
   }

   /**
    * Invoke a static method on the KnotClassLoader-side copy of a class.
    *
    * <p>Matching is by name and runtime argument assignability, not by exact
    * declared type: the Forge server hooks declare {@code MinecraftServer}
    * parameters, so looking them up with {@code Object.class} never matched and
    * every server-phase dispatch failed with NoSuchMethodException. Ambiguity is
    * reported rather than resolved by declaration order.
    *
    * @return true when the target method ran without throwing
    */
   private static boolean invokeInKnot(String className, String methodName, Object... args) {
      try {
         ClassLoader knotCL = Launch.knotLoader.getClassLoader();
         Class<?> target = Class.forName(className, true, knotCL);
         List<java.lang.reflect.Method> candidates = new ArrayList<>();
         for (java.lang.reflect.Method candidate : target.getDeclaredMethods()) {
            if (!candidate.getName().equals(methodName)) continue;
            if (candidate.getParameterCount() != args.length) continue;
            if (!argumentsFit(candidate.getParameterTypes(), args)) continue;
            candidates.add(candidate);
         }
         if (candidates.isEmpty()) {
            LOGGER.warn("{}.{} (arity {}) not found", className, methodName, args.length);
            return false;
         }
         if (candidates.size() > 1) {
            LOGGER.warn("{}.{} is ambiguous ({} candidates); refusing to guess",
                  className, methodName, candidates.size());
            return false;
         }
         java.lang.reflect.Method method = candidates.get(0);
         method.setAccessible(true);
         method.invoke(null, args);
         return true;
      } catch (Throwable t) {
         LOGGER.warn("{}.{} threw", className, methodName, t);
         return false;
      }
   }

   /** True when every non-null argument is assignable to its parameter slot. */
   private static boolean argumentsFit(Class<?>[] paramTypes, Object[] args) {
      for (int i = 0; i < paramTypes.length; i++) {
         if (args[i] == null) {
            if (paramTypes[i].isPrimitive()) return false;
            continue;
         }
         if (paramTypes[i].isPrimitive()) continue; // boxing: let invoke() decide
         if (!paramTypes[i].isInstance(args[i])) return false;
      }
      return true;
   }

   public static MixinEnvironment.Side getSide(){
      return isServer ? MixinEnvironment.Side.SERVER : MixinEnvironment.Side.CLIENT;
   }

   public static void loadConfig() {
      Configs.loadConfig();
   }

   public static void setup(Path gameJarPath) throws ModResolutionException {
      FishModLoader.gameJarPath = gameJarPath;
      FishModLoader.loadConfig();

      //Start mod discovery
      boolean remapRegularMods = FishModLoader.isDevelopmentEnvironment();
      VersionOverrides versionOverrides = new VersionOverrides();
      DependencyOverrides depOverrides = new DependencyOverrides(FishModLoader.CONFIG_DIR.toPath());

      // discover mods

      ModDiscoverer discoverer = new ModDiscoverer(versionOverrides, depOverrides);
      discoverer.addCandidateFinder(new ClasspathModCandidateFinder());
      discoverer.addCandidateFinder(new DirectoryModCandidateFinder(FishModLoader.MOD_DIR.toPath(), remapRegularMods));
      discoverer.addCandidateFinder(new ArgumentModCandidateFinder(remapRegularMods));
      HashMap<String, Set<ModCandidate>> envDisabledModsOut = new HashMap<>();
      List<ModCandidate> modCandidates = discoverer.discoverMods(envDisabledModsOut);
      modCandidates = ModResolver.resolve(modCandidates, FishModLoader.getEnvironmentType(), envDisabledModsOut);
      dumpModList(modCandidates);
      for (ModCandidate modCandidate : modCandidates) {
         if (!modCandidate.hasPath() && !modCandidate.isBuiltin()) {
            try {
               modCandidate.setPaths(Collections.singletonList(modCandidate.copyToDir(MOD_DIR.toPath(), false)));
            } catch (IOException e) {
               throw new RuntimeException("Error extracting mod "+ modCandidate, e);
            }
         }

         addMod(modCandidate);
      }
      Path cacheDir = new File("").toPath().resolve(".fml");
      Path outputdir = cacheDir.resolve("processedMods");
      if (remapRegularMods) {
         if (System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE) == null) {
            MappingConfiguration config = FishModLoader.getMappingConfiguration();
            
            if (!config.getRuntimeNamespace().equals(config.getDefaultModDistributionNamespace())) {
               Log.warn(LogCategory.MOD_REMAP, "Runtime mod remapping disabled due to no fabric.remapClasspathFile being specified. You may need to update loom.");
            }
         } else {
            RuntimeModRemapper.remap(modCandidates, cacheDir.resolve("tmp"), outputdir);
         }
      }
      //Finish mod discovery
      MixinBootstrap.init();
   }

   public static boolean isDevelopmentEnvironment() {
      return IS_DEVELOPMENT;
   }

   public static void setIsServer(boolean isServer) {
      FishModLoader.isServer = isServer;
   }

   public static List<ModCandidate.BuiltinMod> getBuiltinMods() {
      ModDependencyImpl javaDep;
      try {
         javaDep = new ModDependencyImpl(
                 ModDependency.Kind.DEPENDS,
                 "java",
                 List.of(">=17 <=25"));
      } catch (VersionParsingException e) {
         throw new RuntimeException(e);
      }

      return Lists.newArrayList(
              new ModCandidate.BuiltinMod(
                      Collections.singletonList(UrlUtil.asPath(FishModLoader.class
                              .getProtectionDomain()
                              .getCodeSource()
                              .getLocation())),
                      new BuiltinModMetadata.Builder(MOD_ID, VERSION)
                              .setEnvironment(ModEnvironment.UNIVERSAL)
                              .setName("FishLoader")
                              .setDescription("A mod loader for Minecraft is Too Easy Mod")
                              .addAuthor("Modded MITE Community",
                                      Map.of(
                                              "homepage",
                                              "https://minecraftistooeasy.github.io/",
                                              "github",
                                              "https://github.com/MinecraftIsTooEasy"))
                              .addContributor("XiaoYu233", Map.of("github", "https://github.com/XiaoYuOvO"))
                              .addContributor("Xy_Luce", Map.of("github", "https://github.com/ysesiq"))
                              .addContributor("Debris", Map.of("github", "https://github.com/de6ris"))
                              .addLicense("GPLv3")
                              .addIcon(16, "assets/ui/icon/fabric_x16.png")
                              .addDependency(javaDep)
                              .accesswidener("fishmodloader.accesswidener")
                              .build()),
              new ModCandidate.BuiltinMod(
                      Collections.singletonList(gameJarPath),
                      new BuiltinModMetadata.Builder("minecraft", "1.6.4-mite")
                              .setEnvironment(ModEnvironment.UNIVERSAL)
                              .setName("Minecraft")
                              .build()),
               new ModCandidate.BuiltinMod(
                      Collections.singletonList(gameJarPath),
                      new BuiltinModMetadata.Builder("mite", String.valueOf(MITEReleaseAccess.miteRelease))
                              .setEnvironment(ModEnvironment.UNIVERSAL)
                              .setName("Minecraft is Too Easy")
                              .addAuthor("Avernite", Map.of("homepage", "https://avernite.ca"))
                              .build())
      );
   }
}
