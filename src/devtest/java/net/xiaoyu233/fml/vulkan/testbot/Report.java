package net.xiaoyu233.fml.vulkan.testbot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects pass/fail results per step and writes the report file (REPORT-&lt;tag&gt;.md under the docs directory outside the repository, rewritten after every
 * step, so a crash leaves a partial report). Same structure as the report of the user's Twilight Forest test bot (src/devtest of mite-twilightforest).
 */
public final class Report {
    public enum Level { OK, INFO, WARN, FAIL }

    public static final class Section {
        public final String id;
        public final String title;
        long startMs = System.currentTimeMillis();
        long endMs;
        boolean finished;
        boolean skipped;
        int oks;
        int infos;
        int warns;
        int fails;
        final List<String> okNames = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        /** distinct log hits: text -> count */
        final Map<String, Integer> logHits = new LinkedHashMap<>();
        final Map<String, Integer> glErrors = new LinkedHashMap<>();
        final List<String> screenshots = new ArrayList<>();
        /** free-form markdown blocks (tables) rendered after the check lines */
        final List<String> markdown = new ArrayList<>();
        /** vanilla MITE log noise that LogWatch recognised and did NOT count as a finding: description -> count */
        final Map<String, Integer> ignoredNoise = new LinkedHashMap<>();

        Section(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public synchronized void ok(String subject) {
            oks++;
            if (okNames.size() < 3000) {
                okNames.add(subject);
            }
        }

        public synchronized void info(String subject, String detail) {
            infos++;
            lines.add("INFO  " + subject + (detail == null || detail.isEmpty() ? "" : " - " + detail));
        }

        public synchronized void warn(String subject, String detail) {
            warns++;
            lines.add("WARN  " + subject + (detail == null || detail.isEmpty() ? "" : " - " + detail));
        }

        public synchronized void fail(String subject, String detail) {
            fails++;
            lines.add("FAIL  " + subject + (detail == null || detail.isEmpty() ? "" : " - " + detail));
        }

        public void check(boolean ok, String subject, String failDetail) {
            if (ok) {
                ok(subject);
            } else {
                fail(subject, failDetail);
            }
        }

        public synchronized void exception(String subject, Throwable t) {
            fails++;
            StringBuilder sb = new StringBuilder("FAIL  EXCEPTION in ").append(subject).append(": ").append(t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 8); i++) {
                sb.append("\n        at ").append(st[i]);
            }
            if (t.getCause() != null) {
                sb.append("\n      caused by ").append(t.getCause());
                StackTraceElement[] cs = t.getCause().getStackTrace();
                for (int i = 0; i < Math.min(cs.length, 5); i++) {
                    sb.append("\n        at ").append(cs[i]);
                }
            }
            lines.add(sb.toString());
        }

        synchronized void logHit(String line) {
            logHits.merge(line, 1, Integer::sum);
        }

        synchronized void ignoredNoise(String kind) {
            ignoredNoise.merge(kind, 1, Integer::sum);
        }

        synchronized void glError(String where, String what) {
            glErrors.merge(where + ": " + what, 1, Integer::sum);
        }

        public synchronized void markdown(String md) {
            markdown.add(md);
        }

        public synchronized void screenshot(String relPath) {
            screenshots.add(relPath);
        }

        String status() {
            if (skipped) {
                return "SKIPPED";
            }
            if (!finished) {
                return "RUNNING/ABORTED";
            }
            if (fails > 0 || !glErrors.isEmpty() || hasFailingLogHit()) {
                return "FAIL";
            }
            if (warns > 0 || !logHits.isEmpty()) {
                return "WARN";
            }
            return "PASS";
        }

        boolean hasFailingLogHit() {
            for (String l : logHits.keySet()) {
                if (LogWatch.isFailing(l)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final File file;
    private final String tag;
    private final List<Section> sections = new ArrayList<>();
    private final long startMs = System.currentTimeMillis();
    private volatile Section current;
    private String worldNote = "";
    private String fatal;

    public Report(File file, String tag) {
        this.file = file;
        this.tag = tag;
    }

    public Section current() {
        return current;
    }

    public synchronized Section begin(String id, String title) {
        Section s = new Section(id, title);
        sections.add(s);
        current = s;
        LogWatch.setCurrent(s);
        write();
        return s;
    }

    public synchronized Section skipped(String id, String title) {
        Section s = new Section(id, title);
        s.skipped = true;
        s.finished = true;
        sections.add(s);
        return s;
    }

    public synchronized void end(Section s) {
        s.endMs = System.currentTimeMillis();
        s.finished = true;
        if (current == s) {
            current = null;
            LogWatch.setCurrent(null);
        }
        write();
    }

    public void setWorldNote(String note) {
        this.worldNote = note;
    }

    public void setFatal(String f) {
        this.fatal = f;
    }

    private long lastThrottledWriteMs;

    /** Progress heartbeat: rewrites REPORT.md (so its "Updated" line advances during long steps) at most once per {@code minIntervalMs}. */
    public void writeThrottled(long minIntervalMs) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastThrottledWriteMs < minIntervalMs) {
                return;
            }
            lastThrottledWriteMs = now;
        }
        write();
    }

    public synchronized void write() {
        try {
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), render().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[VkTestBot] cannot write report: " + e);
        }
    }

    private String render() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        sb.append("# FML Vulkan test bot report - ").append(tag).append("\n\n");
        sb.append("- Started: ").append(f.format(new Date(startMs))).append("\n");
        sb.append("- Updated: ").append(f.format(new Date())).append("\n");
        sb.append("- Run time: ").append((System.currentTimeMillis() - startMs) / 1000).append(" s\n");
        if (!worldNote.isEmpty()) {
            sb.append("- World: ").append(worldNote).append("\n");
        }
        if (fatal != null) {
            sb.append("- **FATAL / ABORTED**: ").append(fatal).append("\n");
        }
        int pass = 0;
        int warn = 0;
        int fail = 0;
        for (Section s : sections) {
            String st;
            synchronized (s) {
                st = s.status();
            }
            if (st.equals("PASS")) {
                pass++;
            } else if (st.equals("WARN")) {
                warn++;
            } else if (st.equals("FAIL") || st.startsWith("RUNNING")) {
                fail++;
            }
        }
        sb.append("- Steps: ").append(sections.size()).append(" (PASS ").append(pass).append(", WARN ").append(warn)
            .append(", FAIL ").append(fail).append(")\n\n");
        sb.append("Screenshots: `docs/screenshots/").append(tag).append("/`, perf CSV: `docs/perf/`. FAIL = exception, GL error, Vulkan/validation error, \"GL ERROR\" log line or failed assertion. ")
            .append("WARN = suspicious log line or soft check (unsupported GL call, stall, slow chunk meshing, ...).\n\n");

        sb.append("## Summary\n\n| # | Step | Status | OK | WARN | FAIL | Log hits | GL errors | Time |\n|---|---|---|---|---|---|---|---|---|\n");
        int n = 0;
        for (Section s : sections) {
            n++;
            synchronized (s) {
                long ms = (s.finished ? s.endMs : System.currentTimeMillis()) - s.startMs;
                sb.append("| ").append(n).append(" | ").append(s.title).append(" | **").append(s.status()).append("** | ").append(s.oks)
                    .append(" | ").append(s.warns).append(" | ").append(s.fails).append(" | ").append(s.logHits.size())
                    .append(" | ").append(s.glErrors.size()).append(" | ").append(s.skipped ? "-" : ms / 1000 + " s").append(" |\n");
            }
        }
        sb.append("\n");

        for (Section s : sections) {
            synchronized (s) {
                renderSection(sb, s);
            }
        }
        return sb.toString();
    }

    private void renderSection(StringBuilder sb, Section s) {
        {
            sb.append("## ").append(s.title).append(" - ").append(s.status()).append("\n\n");
            if (s.skipped) {
                sb.append("Skipped (-Dfml.vulkan.testbot.skip / testbot.only).\n\n");
                return;
            }
            sb.append("OK checks: ").append(s.oks).append(", info: ").append(s.infos).append(", warnings: ").append(s.warns)
                .append(", failures: ").append(s.fails).append("\n\n");
            if (!s.lines.isEmpty()) {
                sb.append("```\n");
                for (String l : s.lines) {
                    sb.append(l).append("\n");
                }
                sb.append("```\n\n");
            }
            for (String md : s.markdown) {
                sb.append(md).append("\n\n");
            }
            if (!s.glErrors.isEmpty()) {
                sb.append("GL errors (glGetError after a frame/step):\n\n");
                for (Map.Entry<String, Integer> e : s.glErrors.entrySet()) {
                    sb.append("- ").append(e.getKey()).append(" x").append(e.getValue()).append("\n");
                }
                sb.append("\n");
            }
            if (!s.logHits.isEmpty()) {
                sb.append("Log lines captured during this step:\n\n```\n");
                int shown = 0;
                for (Map.Entry<String, Integer> e : s.logHits.entrySet()) {
                    if (shown++ >= 60) {
                        sb.append("... ").append(s.logHits.size() - 60).append(" more distinct lines\n");
                        break;
                    }
                    sb.append(LogWatch.isFailing(e.getKey()) ? "FAIL " : "WARN ").append(e.getKey());
                    if (e.getValue() > 1) {
                        sb.append("  (x").append(e.getValue()).append(")");
                    }
                    sb.append("\n");
                }
                sb.append("```\n\n");
            }
            if (!s.ignoredNoise.isEmpty()) {
                sb.append("Known MITE / FML log noise, ignored (pre-existing, not caused by the Vulkan backend; every line is counted here, never dropped silently):\n\n");
                for (Map.Entry<String, Integer> e : s.ignoredNoise.entrySet()) {
                    sb.append("- ").append(e.getKey()).append(" x").append(e.getValue()).append("\n");
                }
                sb.append("\n");
            }
            if (!s.okNames.isEmpty()) {
                sb.append("<details><summary>Passed (").append(s.oks).append(")</summary>\n\n");
                sb.append(String.join(", ", s.okNames)).append("\n\n</details>\n\n");
            }
            if (!s.screenshots.isEmpty()) {
                sb.append("Screenshots: ");
                int shown = 0;
                for (String sh : s.screenshots) {
                    if (shown++ >= 40) {
                        sb.append("... (+").append(s.screenshots.size() - 40).append(")");
                        break;
                    }
                    sb.append("`").append(sh).append("` ");
                }
                sb.append("\n\n");
            }
        }
    }
}
