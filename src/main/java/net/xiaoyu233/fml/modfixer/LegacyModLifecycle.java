package net.xiaoyu233.fml.modfixer;

import cpw.mods.fml.common.LoaderState;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.relaunch.Launch;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the Forge 1.6.4 mod lifecycle on top of FishModLoader.
 *
 * <p>Workflow:
 * <ol>
 *   <li>{@link #constructAll()} - instantiate every {@code @Mod} class found
 *       by {@link ForgeModDiscoverer}, populate {@code @Instance} fields,
 *       resolve {@code @SidedProxy} fields, and cache {@code @EventHandler}
 *       methods. Emits a {@link FMLConstructionEvent} when done.</li>
 *   <li>{@link #firePreInit()}, {@link #fireInit()}, {@link #firePostInit()}
 *       - the classic three-phase init.</li>
 *   <li>{@link #fireServerStarting(Object)} / {@link #fireServerStarted()}
 *       / {@link #fireServerStopping()} / {@link #fireServerStopped()}
 *       - server lifecycle, called from the {@code MinecraftServer} hooks.</li>
 * </ol>
 *
 * <p>Reflection use is limited to the construction phase: each mod's
 * methods are resolved once and cached as a {@link Map} keyed by event
 * type. Subsequent dispatches just iterate that cache, so per-event cost
 * is bounded by the number of registered handlers.
 */
public final class LegacyModLifecycle {

    private static final String ANN_EVENT_HANDLER = "cpw.mods.fml.common.Mod$EventHandler";
    private static final String ANN_INIT = "cpw.mods.fml.common.Mod$Init";
    private static final String ANN_INSTANCE = "cpw.mods.fml.common.Mod$Instance";
    private static final String ANN_NETWORK_MOD = "cpw.mods.fml.common.network.NetworkMod";
    private static final String ANN_POST_INIT = "cpw.mods.fml.common.Mod$PostInit";
    private static final String ANN_PRE_INIT = "cpw.mods.fml.common.Mod$PreInit";
    private static final String ANN_SERVER_ABOUT_TO_START = "cpw.mods.fml.common.Mod$ServerAboutToStart";
    private static final String ANN_SERVER_STARTED = "cpw.mods.fml.common.Mod$ServerStarted";
    private static final String ANN_SERVER_STARTING = "cpw.mods.fml.common.Mod$ServerStarting";
    private static final String ANN_SERVER_STOPPED = "cpw.mods.fml.common.Mod$ServerStopped";
    private static final String ANN_SERVER_STOPPING = "cpw.mods.fml.common.Mod$ServerStopping";
    private static final String ANN_SIDED_PROXY = "cpw.mods.fml.common.Mod$SidedProxy";
    private static final String ANN_SIDE_ONLY = "cpw.mods.fml.relauncher.SideOnly";

    private static final String EVENT_CONSTRUCTION = "cpw.mods.fml.common.event.FMLConstructionEvent";
    private static final String EVENT_INIT = "cpw.mods.fml.common.event.FMLInitializationEvent";
    private static final String EVENT_POST_INIT = "cpw.mods.fml.common.event.FMLPostInitializationEvent";
    private static final String EVENT_PRE_INIT = "cpw.mods.fml.common.event.FMLPreInitializationEvent";
    private static final String EVENT_SERVER_ABOUT_TO_START = "cpw.mods.fml.common.event.FMLServerAboutToStartEvent";
    private static final String EVENT_SERVER_STARTED = "cpw.mods.fml.common.event.FMLServerStartedEvent";
    private static final String EVENT_SERVER_STARTING = "cpw.mods.fml.common.event.FMLServerStartingEvent";
    private static final String EVENT_SERVER_STOPPED = "cpw.mods.fml.common.event.FMLServerStoppedEvent";
    private static final String EVENT_SERVER_STOPPING = "cpw.mods.fml.common.event.FMLServerStoppingEvent";

    private static final Object[] NO_EVENT_DATA = new Object[0];
    private static final List<LoadedMod> loadedMods = new ArrayList<>();
    private static LoaderState currentState = LoaderState.NOINIT;
    private static Object currentMinecraftServer;

    private LegacyModLifecycle() {}

    public static List<ModContainer> getContainers() {
        List<ModContainer> result = new ArrayList<>(loadedMods.size());
        for (LoadedMod loadedMod : loadedMods) {
            result.add(loadedMod.container);
        }
        return result;
    }

    public static LoaderState getState() {
        return currentState;
    }

    /** Called by FishModLoader after class loader setup, before mixin bootstrap. */
    public static void constructAll() {
        if (currentState != LoaderState.NOINIT) {
            FishModLoader.LOGGER.warn("LegacyModLifecycle.constructAll called twice; skipping");
            return;
        }
        currentState = LoaderState.LOADING;
        for (ForgeModDiscoverer.DiscoveredForgeMod discovered : ForgeModDiscoverer.getDiscovered()) {
            for (LegacyModInfo modInfo : discovered.modAnnotations) {
                try {
                    constructOne(discovered, modInfo);
                } catch (Throwable thrown) {
                    FishModLoader.LOGGER.error("Failed to construct Forge mod {} ({})",
                            modInfo.getModId(), discovered.jarPath.getFileName(), thrown);
                }
            }
        }
        currentState = LoaderState.CONSTRUCTING;
        dispatch(EVENT_CONSTRUCTION);
    }

    private static void constructOne(ForgeModDiscoverer.DiscoveredForgeMod discovered,
                                     LegacyModInfo modInfo) throws Exception {
        Class<?> modClass = Class.forName(modInfo.getModClass(), true, Launch.knotLoader.getClassLoader());
        Object modInstance = modClass.getDeclaredConstructor().newInstance();

        ModMetadata metadata = buildMetadata(modInfo, discovered);
        File jarFile = discovered.jarPath.toFile();
        ForgeModContainer container = new ForgeModContainer(metadata, jarFile, modInstance, modClass);

        injectInstanceFields(modClass, modInstance);
        injectSidedProxies(modClass, modInstance);
        registerNetworkChannels(modClass, modInstance);
        Map<String, List<Method>> handlers = scanHandlers(modClass);

        loadedMods.add(new LoadedMod(container, modInstance, modClass, handlers));
        FishModLoader.LOGGER.info("Constructed Forge mod: {} v{} ({} lifecycle handler(s))",
                metadata.modId, metadata.version, countHandlers(handlers));
    }

    private static ModMetadata buildMetadata(LegacyModInfo modInfo,
                                             ForgeModDiscoverer.DiscoveredForgeMod source) {
        ModMetadata metadata = new ModMetadata();
        metadata.modId   = modInfo.getModId();
        metadata.name    = modInfo.getName();
        metadata.version = modInfo.getVersion();

        // Override from mcmod.info if there's a matching entry by modid.
        for (McModInfoParser.Entry entry : source.mcModInfo) {
            if (metadata.modId == null || !metadata.modId.equals(entry.modid)) continue;
            if (notEmpty(entry.name))        metadata.name = entry.name;
            if (notEmpty(entry.version))     metadata.version = entry.version;
            if (notEmpty(entry.description)) metadata.description = entry.description;
            if (notEmpty(entry.url))         metadata.url = entry.url;
            if (notEmpty(entry.credits))     metadata.credits = entry.credits;
            if (notEmpty(entry.logoFile))    metadata.logoFile = entry.logoFile;
            if (entry.authorList != null && !entry.authorList.isEmpty()) {
                metadata.authorList = new ArrayList<>(entry.authorList);
            }
            break;
        }
        return metadata;
    }

    private static boolean notEmpty(String text) {
        return text != null && !text.isEmpty();
    }

    private static void injectInstanceFields(Class<?> modClass, Object modInstance) {
        for (Field field : modClass.getDeclaredFields()) {
            if (!hasAnnotation(field, ANN_INSTANCE)) continue;
            if (!Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                field.set(null, modInstance);
            } catch (Throwable thrown) {
                FishModLoader.LOGGER.warn("@Instance injection failed on {}.{}",
                        modClass.getName(), field.getName(), thrown);
            }
        }
    }

    private static void injectSidedProxies(Class<?> modClass, Object modInstance) {
        for (Field field : modClass.getDeclaredFields()) {
            Annotation proxyAnn = getAnnotation(field, ANN_SIDED_PROXY);
            if (proxyAnn == null) continue;
            String targetClassName = FishModLoader.isServer()
                    ? annotationString(proxyAnn, "serverSide")
                    : annotationString(proxyAnn, "clientSide");
            if (targetClassName == null || targetClassName.isEmpty()) continue;
            try {
                Class<?> proxyClass = Class.forName(targetClassName, true, Launch.knotLoader.getClassLoader());
                Object proxyInstance = proxyClass.getDeclaredConstructor().newInstance();
                field.setAccessible(true);
                Object target = Modifier.isStatic(field.getModifiers()) ? null : modInstance;
                field.set(target, proxyInstance);
            } catch (Throwable thrown) {
                FishModLoader.LOGGER.warn("@SidedProxy injection failed on {}.{}",
                        modClass.getName(), field.getName(), thrown);
            }
        }
    }

    /**
     * If the mod class is annotated with {@code @NetworkMod} and supplies
     * a {@code packetHandler} class, instantiate it and register against
     * each declared channel via the Forge {@link
     * net.xiaoyu233.fml.forge.network.NetworkRegistry NetworkRegistry}.
     */
    private static void registerNetworkChannels(Class<?> modClass, Object modInstance) {
        Annotation ann = getAnnotation(modClass, ANN_NETWORK_MOD);
        if (ann == null) return;
        String[] channels = annotationStringArray(ann, "channels");
        String handlerClassName = annotationString(ann, "packetHandler");
        if (channels == null || channels.length == 0
                || handlerClassName == null || handlerClassName.isEmpty()) return;

        try {
            Class<?> handlerClass = Class.forName(handlerClassName, true, Launch.knotLoader.getClassLoader());
            Object handlerInstance = handlerClass.getDeclaredConstructor().newInstance();
            Class<?> packetHandlerType = Class.forName(
                    "cpw.mods.fml.common.network.IPacketHandler", false, handlerClass.getClassLoader());
            if (!packetHandlerType.isInstance(handlerInstance)) {
                FishModLoader.LOGGER.warn("@NetworkMod packetHandler {} on {} does not implement IPacketHandler",
                        handlerClassName, modClass.getName());
                return;
            }
            Class<?> registryType = Class.forName(
                    "net.xiaoyu233.fml.forge.network.NetworkRegistry", true, Launch.knotLoader.getClassLoader());
            Object registry = registryType.getMethod("instance").invoke(null);
            Method registerChannel = findMethod(registryType, "registerChannel", 2);
            if (registerChannel == null) {
                FishModLoader.LOGGER.warn("NetworkRegistry.registerChannel is unavailable");
                return;
            }
            for (String channel : channels) {
                registerChannel.invoke(registry, handlerInstance, channel);
            }
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.warn("Failed to wire @NetworkMod packetHandler {} for {}",
                    handlerClassName, modClass.getName(), thrown);
        }
    }

    private static Map<String, List<Method>> scanHandlers(Class<?> modClass) {
        Map<String, List<Method>> result = new HashMap<>();
        boolean isClientSide = !FishModLoader.isServer();
        for (Method method : modClass.getDeclaredMethods()) {
            String eventType = inferEventType(method);
            if (eventType == null) continue;

            Annotation sideOnly = getAnnotation(method, ANN_SIDE_ONLY);
            if (sideOnly != null) {
                Object side = annotationValue(sideOnly, "value");
                if (("CLIENT".equals(String.valueOf(side))) != isClientSide) continue;
            }

            method.setAccessible(true);
            result.computeIfAbsent(eventType, key -> new ArrayList<>()).add(method);
        }
        return result;
    }

    /**
     * Map a handler method to the event class it should fire on. Returns
     * null if the method is not annotated with any of the @Mod lifecycle
     * markers.
     */
    private static String inferEventType(Method method) {
        if (hasAnnotation(method, ANN_PRE_INIT)) return EVENT_PRE_INIT;
        if (hasAnnotation(method, ANN_INIT)) return EVENT_INIT;
        if (hasAnnotation(method, ANN_POST_INIT)) return EVENT_POST_INIT;
        if (hasAnnotation(method, ANN_SERVER_ABOUT_TO_START)) return EVENT_SERVER_ABOUT_TO_START;
        if (hasAnnotation(method, ANN_SERVER_STARTING)) return EVENT_SERVER_STARTING;
        if (hasAnnotation(method, ANN_SERVER_STARTED)) return EVENT_SERVER_STARTED;
        if (hasAnnotation(method, ANN_SERVER_STOPPING)) return EVENT_SERVER_STOPPING;
        if (hasAnnotation(method, ANN_SERVER_STOPPED)) return EVENT_SERVER_STOPPED;
        if (hasAnnotation(method, ANN_EVENT_HANDLER)) {
            // Generic @EventHandler - read the param type to know which event.
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && isFmlStateEvent(params[0])) {
                return params[0].getName();
            }
        }
        return null;
    }

    public static void firePreInit() {
        currentState = LoaderState.PREINITIALIZATION;
        dispatchPreInit();
    }

    public static void fireInit() {
        currentState = LoaderState.INITIALIZATION;
        dispatch(EVENT_INIT);
    }

    public static void firePostInit() {
        currentState = LoaderState.POSTINITIALIZATION;
        dispatch(EVENT_POST_INIT);
        currentState = LoaderState.AVAILABLE;
    }

    public static void fireServerAboutToStart(Object server) {
        currentState = LoaderState.SERVER_ABOUT_TO_START;
        rememberServer(server);
        FishModLoader.LOGGER.info("Firing Forge FMLServerAboutToStartEvent");
        dispatch(EVENT_SERVER_ABOUT_TO_START, currentMinecraftServer);
    }

    public static void fireServerStarting(Object server) {
        currentState = LoaderState.SERVER_STARTING;
        rememberServer(server);
        FishModLoader.LOGGER.info("Firing Forge FMLServerStartingEvent");
        List<Object> events = dispatch(EVENT_SERVER_STARTING, currentMinecraftServer);
        registerServerCommands(currentMinecraftServer, events);
    }

    public static void fireServerStarted() {
        currentState = LoaderState.SERVER_STARTED;
        FishModLoader.LOGGER.info("Firing Forge FMLServerStartedEvent");
        dispatch(EVENT_SERVER_STARTED);
        installForgeSlashCommandAliases();
        logForgeStyleCommands();
    }

    public static void fireServerStopping() {
        currentState = LoaderState.SERVER_STOPPING;
        FishModLoader.LOGGER.info("Firing Forge FMLServerStoppingEvent");
        dispatch(EVENT_SERVER_STOPPING);
    }

    public static void fireServerStopped() {
        currentState = LoaderState.SERVER_STOPPED;
        FishModLoader.LOGGER.info("Firing Forge FMLServerStoppedEvent");
        dispatch(EVENT_SERVER_STOPPED);
    }

    private static void rememberServer(Object server) {
        if (server == null) return;
        currentMinecraftServer = server;
        try {
            ClassLoader loader = Launch.knotLoader.getClassLoader();
            Class<?> commonHandler = Class.forName("cpw.mods.fml.common.FMLCommonHandler", true, loader);
            Object instance = commonHandler.getMethod("instance").invoke(null);
            Method setter = findMethod(commonHandler, "setMinecraftServerInstance", 1);
            if (setter != null) {
                setter.invoke(instance, server);
            }
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.warn("Failed to cache Forge MinecraftServer instance", thrown);
        }
    }

    private static void dispatchPreInit() {
        for (LoadedMod loadedMod : loadedMods) {
            List<Method> handlers = loadedMod.handlers.get(EVENT_PRE_INIT);
            if (handlers == null) continue;
            Object event;
            try {
                event = newEvent(loadedMod.modClass.getClassLoader(),
                        EVENT_PRE_INIT,
                        FishModLoader.CONFIG_DIR,
                        loadedMod.container.getSource());
            } catch (Throwable thrown) {
                FishModLoader.LOGGER.error("Failed to create {} for Forge mod {}",
                        simpleName(EVENT_PRE_INIT), loadedMod.container.getModId(), thrown);
                continue;
            }
            invokeHandlers(loadedMod, EVENT_PRE_INIT, event, handlers);
        }
    }

    private static List<Object> dispatch(String eventType, Object... eventData) {
        List<Object> firedEvents = new ArrayList<>();
        for (LoadedMod loadedMod : loadedMods) {
            List<Method> handlers = loadedMod.handlers.get(eventType);
            if (handlers == null) continue;
            Object event;
            try {
                event = newEvent(loadedMod.modClass.getClassLoader(), eventType, eventData);
            } catch (Throwable thrown) {
                FishModLoader.LOGGER.error("Failed to create {} for Forge mod {}",
                        simpleName(eventType), loadedMod.container.getModId(), thrown);
                continue;
            }
            firedEvents.add(event);
            invokeHandlers(loadedMod, eventType, event, handlers);
        }
        return firedEvents;
    }

    private static void invokeHandlers(LoadedMod loadedMod, String eventType, Object event, List<Method> handlers) {
        FishModLoader.LOGGER.info("Dispatching {} to {} handler(s) on Forge mod {}",
                simpleName(eventType), handlers.size(), loadedMod.container.getModId());
        for (Method handler : handlers) {
            try {
                handler.invoke(loadedMod.instance, event);
            } catch (InvocationTargetException invocationException) {
                FishModLoader.LOGGER.error("Mod {} threw during {}",
                        loadedMod.container.getModId(),
                        simpleName(eventType),
                        invocationException.getCause());
            } catch (Throwable thrown) {
                FishModLoader.LOGGER.error("Failed to invoke handler {}.{}",
                        loadedMod.modClass.getName(), handler.getName(), thrown);
            }
        }
    }

    private static void registerServerCommands(Object server, List<Object> events) {
        if (server == null || events.isEmpty()) return;
        try {
            Object commandManager = server.getClass().getMethod("getCommandManager").invoke(server);
            if (commandManager == null) return;
            Method registerCommand = findMethod(commandManager.getClass(), "registerCommand", 1);
            if (registerCommand == null) {
                FishModLoader.LOGGER.warn("Forge mods registered server command(s), but command manager {} cannot accept them",
                        commandManager.getClass().getName());
                return;
            }
            for (Object event : events) {
                Method getRegisteredCommands = findMethod(event.getClass(), "getRegisteredCommands", 0);
                if (getRegisteredCommands == null) continue;
                Object commandsObject = getRegisteredCommands.invoke(event);
                if (!(commandsObject instanceof List)) continue;
                for (Object command : (List<?>) commandsObject) {
                    try {
                        registerCommand.invoke(commandManager, command);
                    } catch (InvocationTargetException invocationException) {
                        FishModLoader.LOGGER.warn("Failed to register Forge server command {}",
                                command == null ? "null" : commandName(command), invocationException.getCause());
                    }
                }
            }
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.warn("Failed to register Forge server command(s)", thrown);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void installForgeSlashCommandAliases() {
        Object server = currentMinecraftServer;
        if (server == null) return;

        Map commands;
        try {
            Object commandManager = server.getClass().getMethod("getCommandManager").invoke(server);
            if (commandManager == null) return;
            Object value = commandManager.getClass().getMethod("getCommands").invoke(commandManager);
            if (!(value instanceof Map)) return;
            commands = (Map) value;
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.warn("Failed to install Forge slash command aliases", thrown);
            return;
        }

        List<String> aliasKeys = new ArrayList<>();
        List<Object> aliasCommands = new ArrayList<>();
        for (Object entryObject : commands.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObject;
            Object key = entry.getKey();
            if (!(key instanceof String)) continue;
            String slashAlias = (String) key;
            if (!slashAlias.startsWith("/") || slashAlias.length() <= 1) continue;

            String vanillaAlias = slashAlias.substring(1);
            if (commands.containsKey(vanillaAlias)) continue;
            aliasKeys.add(vanillaAlias);
            aliasCommands.add(entry.getValue());
        }

        for (int i = 0; i < aliasKeys.size(); i++) {
            commands.put(aliasKeys.get(i), aliasCommands.get(i));
        }

        if (!aliasKeys.isEmpty()) {
            FishModLoader.LOGGER.info("Registered {} no-slash aliases for Forge slash commands ({})",
                    aliasKeys.size(), sampleAliases(aliasKeys));
        }
    }

    private static void logForgeStyleCommands() {
        Object server = currentMinecraftServer;
        if (server == null) return;
        Object commandManager;
        try {
            commandManager = server.getClass().getMethod("getCommandManager").invoke(server);
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.warn("Failed to inspect Forge-style command aliases", thrown);
            return;
        }
        if (commandManager == null) return;

        int slashCommandCount = 0;
        StringBuilder sample = new StringBuilder();
        Map<?, ?> commands;
        try {
            Object value = commandManager.getClass().getMethod("getCommands").invoke(commandManager);
            if (!(value instanceof Map)) return;
            commands = (Map<?, ?>) value;
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.warn("Failed to inspect Forge-style command aliases", thrown);
            return;
        }

        for (Object key : commands.keySet()) {
            if (key instanceof String && ((String) key).startsWith("/")) {
                slashCommandCount++;
                if (sample.length() < 120) {
                    if (sample.length() > 0) sample.append(", ");
                    sample.append(key);
                }
            }
        }

        if (slashCommandCount > 0) {
            FishModLoader.LOGGER.info("Registered {} Forge-style slash command aliases ({})",
                    slashCommandCount, sample);
        } else {
            FishModLoader.LOGGER.warn("No Forge-style slash command aliases were registered");
        }
    }

    private static String sampleAliases(List<String> aliases) {
        StringBuilder sample = new StringBuilder();
        for (String alias : aliases) {
            if (sample.length() >= 120) break;
            if (sample.length() > 0) sample.append(", ");
            sample.append(alias);
        }
        return sample.toString();
    }

    private static boolean hasAnnotation(AnnotatedElement element, String annotationName) {
        return getAnnotation(element, annotationName) != null;
    }

    private static Annotation getAnnotation(AnnotatedElement element, String annotationName) {
        for (Annotation annotation : element.getAnnotations()) {
            if (annotation.annotationType().getName().equals(annotationName)) {
                return annotation;
            }
        }
        return null;
    }

    private static Object annotationValue(Annotation annotation, String methodName) {
        try {
            return annotation.annotationType().getMethod(methodName).invoke(annotation);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String annotationString(Annotation annotation, String methodName) {
        Object value = annotationValue(annotation, methodName);
        return value instanceof String ? (String) value : null;
    }

    private static String[] annotationStringArray(Annotation annotation, String methodName) {
        Object value = annotationValue(annotation, methodName);
        return value instanceof String[] ? (String[]) value : null;
    }

    private static boolean isFmlStateEvent(Class<?> type) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            if ("cpw.mods.fml.common.event.FMLStateEvent".equals(cursor.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Object newEvent(ClassLoader classLoader, String eventType, Object... eventData) throws Exception {
        Class<?> eventClass = Class.forName(eventType, true, classLoader);
        Constructor<?> constructor = eventClass.getConstructor(Object[].class);
        return constructor.newInstance((Object) (eventData == null ? NO_EVENT_DATA : eventData));
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().length == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static String commandName(Object command) {
        if (command == null) return "null";
        try {
            Method getCommandName = findMethod(command.getClass(), "getCommandName", 0);
            Object value = getCommandName == null ? null : getCommandName.invoke(command);
            return String.valueOf(value);
        } catch (Throwable ignored) {
            return command.getClass().getName();
        }
    }

    private static int countHandlers(Map<String, List<Method>> handlers) {
        int count = 0;
        for (List<Method> methods : handlers.values()) {
            count += methods.size();
        }
        return count;
    }

    private static String simpleName(String className) {
        int index = className.lastIndexOf('.');
        return index >= 0 ? className.substring(index + 1) : className;
    }

    /** Holds runtime data for a constructed Forge mod. */
    private static final class LoadedMod {
        final ForgeModContainer container;
        final Object instance;
        final Class<?> modClass;
        final Map<String, List<Method>> handlers;

        LoadedMod(ForgeModContainer container,
                  Object instance,
                  Class<?> modClass,
                  Map<String, List<Method>> handlers) {
            this.container = container;
            this.instance = instance;
            this.modClass = modClass;
            this.handlers = handlers;
        }
    }
}
