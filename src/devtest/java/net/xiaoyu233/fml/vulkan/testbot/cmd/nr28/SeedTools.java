package net.xiaoyu233.fml.vulkan.testbot.cmd.nr28;

import net.xiaoyu233.fml.vulkan.testbot.Report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Merges the per-user pipeline journal into native-pipelines.seed (unique, sorted, header kept). */
final class SeedTools {
    private SeedTools() {
    }

    /** args[0] = seed file (default src/main/resources/assets/fml/native-pipelines.seed), args[1] = journal (default gameDir/.fml/vulkan/native-pipelines.txt). */
    static void refresh(Report.Section s, String[] args) throws Exception {
        Path seed = Paths.get(args.length > 0 && !args[0].isEmpty() ? args[0] : "src/main/resources/assets/fml/native-pipelines.seed");
        Path journal = args.length > 1 && !args[1].isEmpty() ? Paths.get(args[1])
                : Paths.get(System.getProperty("user.dir"), ".fml", "vulkan", "native-pipelines.txt");
        if (!Files.isRegularFile(journal)) {
            s.fail("seedRefresh", "journal not found: " + journal);
            return;
        }
        List<String> header = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        if (Files.isRegularFile(seed)) {
            for (String l : Files.readAllLines(seed, StandardCharsets.UTF_8)) {
                String t = l.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    if (!t.isEmpty()) {
                        header.add(l);
                    }
                } else {
                    keys.add(normalize(t));
                }
            }
        }
        int before = keys.size();
        int read = 0;
        for (String l : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            read++;
            keys.add(normalize(t));
        }
        List<String> out = new ArrayList<>(header);
        out.addAll(keys);
        Files.write(seed, out, StandardCharsets.UTF_8);
        int added = keys.size() - before;
        s.info("seedRefresh", "journal lines " + read + ", added keys " + added + ", total " + keys.size() + " -> " + seed);
        s.ok("seedRefresh");
    }

    private static String normalize(String t) {
        return t.replaceAll("\\s+", " ");
    }
}
