package net.xiaoyu233.fml.vulkan.testbot.cmd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of the devtest commands of all native-rewrite packages. At first use it tries
 * {@code Class.forName("net.xiaoyu233.fml.vulkan.testbot.cmd.nrNN.Commands")} for NN = 00..29 and registers what every
 * {@link DevCommandProvider} returns. A missing class is normal (that package has not landed in this tree); a provider that fails to
 * load or to list its commands is recorded in {@link #problems()} and does not stop the others.
 */
public final class DevCommands {
    public static final int PACKAGE_COUNT = 30;
    private static final String PROVIDER_PATTERN = "net.xiaoyu233.fml.vulkan.testbot.cmd.nr%02d.Commands";

    private static final Object LOCK = new Object();
    private static List<DevCommandProvider> providers;
    private static Map<String, DevCommand> byName;
    private static List<String> problems;

    private DevCommands() {
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (providers != null) {
                return;
            }
            List<DevCommandProvider> found = new ArrayList<>();
            Map<String, DevCommand> commands = new LinkedHashMap<>();
            List<String> issues = new ArrayList<>();
            for (int nn = 0; nn < PACKAGE_COUNT; nn++) {
                String className = String.format(Locale.ROOT, PROVIDER_PATTERN, nn);
                Class<?> type;
                try {
                    type = Class.forName(className, true, DevCommands.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    continue;
                } catch (Throwable t) {
                    issues.add(className + ": " + t);
                    continue;
                }
                try {
                    DevCommandProvider provider = (DevCommandProvider) type.getDeclaredConstructor().newInstance();
                    found.add(provider);
                    for (DevCommand command : provider.commands()) {
                        String key = command.name().toLowerCase(Locale.ROOT);
                        DevCommand previous = commands.putIfAbsent(key, command);
                        if (previous != null) {
                            issues.add(className + ": duplicate command name '" + command.name() + "'");
                        }
                    }
                } catch (Throwable t) {
                    issues.add(className + ": " + t);
                }
            }
            providers = Collections.unmodifiableList(found);
            byName = Collections.unmodifiableMap(commands);
            problems = Collections.unmodifiableList(issues);
        }
    }

    /** The providers found, in package order. */
    public static List<DevCommandProvider> providers() {
        ensureLoaded();
        return providers;
    }

    /** All commands in package order. */
    public static List<DevCommand> all() {
        ensureLoaded();
        return List.copyOf(byName.values());
    }

    /** The command with that name (case-insensitive) or null. */
    public static DevCommand find(String name) {
        ensureLoaded();
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    /** Providers that could not be loaded and duplicate names; empty when everything is fine. */
    public static List<String> problems() {
        ensureLoaded();
        return problems;
    }

    /** Markdown listing for the report: one line per provider with its command names. */
    public static String describe() {
        ensureLoaded();
        StringBuilder sb = new StringBuilder();
        sb.append(providers.size()).append(" of ").append(PACKAGE_COUNT).append(" command providers, ").append(byName.size()).append(" commands\n");
        for (DevCommandProvider p : providers) {
            sb.append("* ").append(p.packageId()).append(": ");
            List<DevCommand> list = p.commands();
            if (list.isEmpty()) {
                sb.append("(no commands)");
            }
            for (int i = 0; i < list.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(list.get(i).name());
            }
            sb.append('\n');
        }
        for (String problem : problems) {
            sb.append("* PROBLEM ").append(problem).append('\n');
        }
        return sb.toString();
    }
}
