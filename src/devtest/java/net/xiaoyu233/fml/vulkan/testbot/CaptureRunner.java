package net.xiaoyu233.fml.vulkan.testbot;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.minecraft.GuiControls;
import net.minecraft.GuiCreateWorld;
import net.minecraft.GuiIngameMenu;
import net.minecraft.GuiInventory;
import net.minecraft.GuiLanguage;
import net.minecraft.GuiMainMenu;
import net.minecraft.GuiMultiplayer;
import net.minecraft.GuiOptions;
import net.minecraft.GuiSelectWorld;
import net.minecraft.GuiVideoSettings;
import net.minecraft.EnumGameType;
import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.testbot.ImageStats.Comparison;
import net.xiaoyu233.fml.vulkan.testbot.ImageStats.Tolerance;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommands;

/**
 * Capture runner (7.2): reads every {@code native-captures/NR-<NN>.txt} of the class path and captures each scene in frozen-world conditions.
 *
 * <h2>Scene list format</h2>
 * One scene per line, {@code #} starts a comment, blank lines are ignored:
 * <pre>
 * scene &lt;id&gt; &lt;T0|T1|T2&gt; &lt;kind&gt; [key=value ...]
 * </pre>
 * {@code id} is {@code [A-Za-z0-9_]+} and unique over all files (the runner prefixes the file name of the capture with the package id). Kinds:
 * <ul>
 * <li>{@code menu name=<title|options|video|controls|language|singleplayer|createworld|multiplayer>}: a title-context screen (no world), run in step 01c, before the world exists.
 * {@code panorama=<n>} pins the panorama timer.</li>
 * <li>{@code world pose=<dx>,<dy>,<dz>,<yaw>,<pitch>}: the player at the museum centre + (dx, dy, dz) (feet y = 181 + dy), optional {@code time=<adjusted time of day>},
 * {@code rain=0|1}, {@code view=0|1|2} (third person), {@code gui=inventory|pause}, {@code mode=creative|survival}, {@code partial=<0..1>} (render partial tick, default 0.5),
 * {@code chat=keep} (chat lines are cleared before a capture by default).</li>
 * <li>{@code cmd name=<devtest command> [args=<text>]}: world scene whose set-up is a {@link DevCommand} of the owning package (entity zoo, particles, containers ...): the command
 * runs first (it may use {@link Frozen} and the {@link Stage}), then the runner waits for a quiet renderer and captures. Pose keys are applied before the command.</li>
 * </ul>
 * Every scene is captured as {@code scene_<id>.png} into the capture directory of the run (tag), and {@code scenes.tsv} (id, tolerance, package) is written next to them so that
 * {@link CaptureCompare} knows the tolerance class of every file. Properties: {@code fml.vulkan.testbot.scenes} (comma separated regex list, only matching ids run),
 * {@code fml.vulkan.testbot.repeat=true} (each scene is captured twice in the same run and compared T0: determinism of the capture set-up).
 */
public final class CaptureRunner {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_]+");
    private static final List<Scene> SCENES = load();
    private static final String MANIFEST = "scenes.tsv";

    /** One parsed scene line. */
    public record Scene(String packageId, String id, Tolerance tolerance, String kind, Map<String, String> options, String source) {
        public boolean isMenu() {
            return kind.equals("menu");
        }

        public String option(String key, String def) {
            return options.getOrDefault(key, def);
        }

        public String fileName() {
            return "scene_" + id;
        }
    }

    private final TestBot bot;
    private final Stage stage;
    private final Minecraft mc;
    private final List<String> manifest = new ArrayList<>();

    public CaptureRunner(TestBot bot, Stage stage) {
        this.bot = bot;
        this.stage = stage;
        this.mc = bot.mc;
    }

    // ------------------------------------------------------------------ scene list

    public static List<Scene> scenes() {
        return SCENES;
    }

    public static boolean hasWorldScenes() {
        for (Scene s : SCENES) {
            if (!s.isMenu() && matches(s.id())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String id) {
        String filter = System.getProperty("fml.vulkan.testbot.scenes", "").trim();
        if (filter.isEmpty()) {
            return true;
        }
        for (String p : filter.split(",")) {
            if (!p.isBlank() && Pattern.compile(p.trim()).matcher(id).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<Scene> load() {
        List<Scene> out = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        ClassLoader cl = CaptureRunner.class.getClassLoader();
        for (int nn = 0; nn < DevCommands.PACKAGE_COUNT; nn++) {
            String pkg = String.format(Locale.ROOT, "NR-%02d", nn);
            String path = "native-captures/" + pkg + ".txt";
            try (InputStream in = cl.getResourceAsStream(path)) {
                if (in == null) {
                    continue;
                }
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                int lineNo = 0;
                while ((line = r.readLine()) != null) {
                    lineNo++;
                    String t = line.strip();
                    if (t.isEmpty() || t.startsWith("#")) {
                        continue;
                    }
                    Scene s = parse(pkg, t, path + ":" + lineNo);
                    String prev = seen.putIfAbsent(s.id(), s.source());
                    if (prev != null) {
                        throw new IllegalStateException("duplicate scene id '" + s.id() + "' in " + s.source() + " and " + prev);
                    }
                    out.add(s);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + path + ": " + e, e);
            }
        }
        return java.util.Collections.unmodifiableList(out);
    }

    static Scene parse(String pkg, String line, String source) {
        String[] tok = line.split("\\s+");
        if (tok.length < 4 || !tok[0].equals("scene")) {
            throw new IllegalArgumentException(source + ": expected 'scene <id> <T0|T1|T2> <kind> [key=value ...]', got '" + line + "'");
        }
        if (!ID.matcher(tok[1]).matches()) {
            throw new IllegalArgumentException(source + ": bad scene id '" + tok[1] + "'");
        }
        Tolerance tol;
        try {
            tol = Tolerance.parse(tok[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + ": bad tolerance class '" + tok[2] + "' (T0, T1 or T2)");
        }
        String kind = tok[3];
        if (!kind.equals("menu") && !kind.equals("world") && !kind.equals("cmd")) {
            throw new IllegalArgumentException(source + ": unknown scene kind '" + kind + "' (menu, world, cmd)");
        }
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 4; i < tok.length; i++) {
            int eq = tok[i].indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException(source + ": bad option '" + tok[i] + "' (key=value)");
            }
            opts.put(tok[i].substring(0, eq), tok[i].substring(eq + 1).replace('+', ' '));
        }
        return new Scene(pkg, tok[1], tol, kind, opts, source);
    }

    // ------------------------------------------------------------------ steps

    /** Step 01c: menu scenes (the main menu is visible, no world). */
    public void runTitleScenes(Report.Section s) throws Exception {
        List<Scene> list = select(true);
        if (list.isEmpty()) {
            s.info("scenes", "no menu scenes selected");
            return;
        }
        freezeAtlas(s);
        boolean menu = bot.waitFor(() -> mc.theWorld == null && mc.currentScreen instanceof GuiMainMenu, 3000);
        s.check(menu, "main menu visible", "main menu not reachable");
        if (!menu) {
            return;
        }
        for (Scene sc : list) {
            runScene(s, sc);
        }
        bot.client(() -> mc.displayGuiScreen(new GuiMainMenu()));
        bot.frames(5);
        bot.client(Frozen::releaseAtlas);
        finish(s);
    }

    /** Step 05: world scenes. */
    public void runWorldScenes(Report.Section s) throws Exception {
        List<Scene> list = select(false);
        if (list.isEmpty()) {
            s.info("scenes", "no world scenes selected");
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            s.fail("world", "no world: world scenes cannot run");
            return;
        }
        freezeAtlas(s);
        stage.prepPlayer();
        stage.ensureMuseum();
        // MITE gives a new character a random starting item (a stick, a flint, ...): the held item must not depend on it
        bot.server(() -> {
            net.minecraft.ServerPlayer p = sp();
            java.util.Arrays.fill(p.inventory.mainInventory, null);
            java.util.Arrays.fill(p.inventory.armorInventory, null);
            p.inventory.currentItem = 0;
            p.inventoryContainer.detectAndSendChanges();
        });
        bot.client(() -> {
            java.util.Arrays.fill(mc.thePlayer.inventory.mainInventory, null);
            java.util.Arrays.fill(mc.thePlayer.inventory.armorInventory, null);
            mc.thePlayer.inventory.currentItem = 0;
        });
        bot.ticks(5);
        bot.server(() -> sp().inventory.currentItem = 0);
        for (Scene sc : list) {
            runScene(s, sc);
        }
        Frozen.releaseEntities();
        Frozen.unlockWeather();
        bot.client(Frozen::releaseAtlas);
        stage.prepPlayer();
        finish(s);
    }

    private void freezeAtlas(Report.Section s) throws Exception {
        if (Frozen.active()) {
            int n = bot.client(() -> Frozen.freezeAtlas(mc));
            s.info("atlas", "animated sprites brought to frame 0 (" + n + " animation steps) and frozen for the capture route");
        } else {
            s.warn("frozen mode", "captures run without -Pvk.frozen=true: they are not reproducible (testbotCaptures sets it)");
        }
    }

    private net.minecraft.ServerPlayer sp() {
        return (net.minecraft.ServerPlayer) net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
    }

    private List<Scene> select(boolean menus) {
        List<Scene> out = new ArrayList<>();
        for (Scene sc : SCENES) {
            if (sc.isMenu() == menus && matches(sc.id())) {
                out.add(sc);
            }
        }
        return out;
    }

    private void finish(Report.Section s) throws IOException {
        Path file = bot.shots.resolve(MANIFEST);
        List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
        }
        for (String m : manifest) {
            if (!lines.contains(m)) {
                lines.add(m);
            }
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        s.info("manifest", lines.size() + " scene(s) in " + file);
        manifest.clear();
    }

    // ------------------------------------------------------------------ one scene

    private void runScene(Report.Section s, Scene sc) {
        String name = sc.packageId() + "/" + sc.id();
        try {
            bot.status("capture " + name);
            setUp(s, sc);
            String file = capture(s, sc, sc.fileName());
            manifest.add(sc.id() + "\t" + sc.tolerance() + "\t" + sc.packageId() + "\t" + sc.fileName() + ".png");
            if (file != null && Boolean.getBoolean("fml.vulkan.testbot.repeat")) {
                String again = capture(s, sc, sc.fileName() + "_rep");
                if (again != null) {
                    BufferedImage a = ImageStats.read(file);
                    BufferedImage b = ImageStats.read(again);
                    Comparison exact = ImageStats.compare(a, b, Tolerance.T0, null);
                    if (sc.tolerance() == Tolerance.T0) {
                        s.check(exact.pass(), "scene " + name + " captured twice is byte-identical (frozen set-up deterministic)", exact.summary());
                    } else if (exact.pass()) {
                        s.ok("scene " + name + " captured twice is byte-identical");
                    } else {
                        // 3D scenes may differ by a few pixels run to run (draw order of equal-distance renderers, alpha-tested leaves): the set-up must still be inside the scene's own class
                        Comparison own = ImageStats.compare(a, b, sc.tolerance(), null);
                        s.check(own.pass(), "scene " + name + " captured twice stays inside " + sc.tolerance() + " (not byte-identical: " + exact.maxDiff() + " max diff, "
                            + (exact.gatedPixels() - exact.histogram()[0]) + " pixels)", own.summary());
                    }
                }
            }
        } catch (Throwable t) {
            s.exception("scene " + name, t);
        } finally {
            try {
                bot.client(() -> {
                    Frozen.endCapture();
                    if (mc.theWorld != null) {
                        mc.displayGuiScreen(null);
                    }
                });
            } catch (Exception ignored) {
                // run aborted
            }
        }
    }

    private void setUp(Report.Section s, Scene sc) throws Exception {
        if (sc.isMenu()) {
            String n = sc.option("name", "title");
            bot.client(() -> {
                GuiMainMenu parent = new GuiMainMenu();
                switch (n) {
                    case "title" -> mc.displayGuiScreen(parent);
                    case "options" -> mc.displayGuiScreen(new GuiOptions(parent, mc.gameSettings));
                    case "video" -> mc.displayGuiScreen(new GuiVideoSettings(parent, mc.gameSettings));
                    case "controls" -> mc.displayGuiScreen(new GuiControls(parent, mc.gameSettings));
                    case "language" -> mc.displayGuiScreen(new GuiLanguage(parent, mc.gameSettings, mc.getLanguageManager()));
                    case "singleplayer" -> mc.displayGuiScreen(new GuiSelectWorld(parent));
                    case "createworld" -> mc.displayGuiScreen(new GuiCreateWorld(parent));
                    case "multiplayer" -> mc.displayGuiScreen(new GuiMultiplayer(parent));
                    default -> throw new IllegalArgumentException("unknown menu '" + n + "' in " + sc.source());
                }
                if (mc.currentScreen instanceof GuiMainMenu m) {
                    Frozen.setPanoramaTimer(Integer.parseInt(sc.option("panorama", "40")));
                    Frozen.setField(m, "splashText", "Frozen!");
                }
            });
            bot.frames(10);
            return;
        }
        double[] c = stage.ensureMuseum();
        String pose = sc.option("pose", "0,0,-8,0,12");
        String[] p = pose.split(",");
        if (p.length != 5) {
            throw new IllegalArgumentException(sc.source() + ": pose needs dx,dy,dz,yaw,pitch");
        }
        String mode = sc.option("mode", "creative");
        boolean survival = mode.equals("survival");
        bot.client(() -> {
            mc.displayGuiScreen(null);
            mc.gameSettings.thirdPersonView = Integer.parseInt(sc.option("view", "0"));
            mc.gameSettings.showDebugInfo = false;
        });
        bot.server(() -> {
            net.minecraft.ServerPlayer pl = sp();
            pl.setGameType(survival ? EnumGameType.SURVIVAL : EnumGameType.CREATIVE);
            pl.capabilities.disableDamage = true;
            pl.capabilities.isFlying = !survival;
            pl.capabilities.allowFlying = true;
            pl.sendPlayerAbilities();
        });
        boolean flying = !survival;
        bot.waitFor(() -> mc.thePlayer.capabilities.isFlying == flying, 100);
        stage.place(c[0] + Double.parseDouble(p[0]), c[1] + Double.parseDouble(p[1]), c[2] + Double.parseDouble(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4]));
        String time = sc.option("time", "12000");
        stage.setTimeOfDay(Long.parseLong(time));
        float rain = Float.parseFloat(sc.option("rain", "0"));
        bot.client(() -> Frozen.lockWeather(mc.theWorld, rain));
        if (sc.kind().equals("cmd")) {
            String cmd = sc.option("name", "");
            DevCommand dc = DevCommands.find(cmd);
            if (dc == null) {
                throw new IllegalArgumentException(sc.source() + ": no devtest command '" + cmd + "' (package not landed in this tree?)");
            }
            dc.run(s, sc.option("args", "").isBlank() ? new String[0] : sc.option("args", "").split("\\s+"));
        }
        String gui = sc.option("gui", "");
        if (!gui.isEmpty()) {
            bot.client(() -> mc.displayGuiScreen(gui.equals("inventory") ? new GuiInventory(mc.thePlayer) : new GuiIngameMenu()));
        }
    }

    private String capture(Report.Section s, Scene sc, String file) throws Exception {
        if (!sc.isMenu()) {
            boolean quiet = stage.quietFrames(20, 1500);
            if (!quiet) {
                s.warn("scene " + sc.id(), "renderer did not reach 20 quiet frames within 1500 frames; captured anyway");
            }
        } else {
            bot.frames(5);
        }
        float partial = Float.parseFloat(sc.option("partial", Float.toString(Frozen.DEFAULT_PARTIAL_TICKS)));
        boolean keepChat = sc.option("chat", "clear").equals("keep");
        bot.client(() -> {
            if (!keepChat && mc.ingameGUI != null) {
                // chat lines fade with the GUI update counter (real time): the "game mode updated" message of the set-up must not be in a capture
                mc.ingameGUI.getChatGUI().clearChatMessages();
            }
            Frozen.pinClientCounters(mc);
            Frozen.beginCapture(partial);
        });
        bot.frames(3);
        // the first frame of the window may still have used the real partial tick (the timer is forced from the next loop iteration on): recompile the clouds with the forced one
        bot.client(() -> Frozen.pinClientCounters(mc));
        bot.frames(3);
        String state = bot.client(() -> Frozen.fingerprint(mc));
        String path = bot.screenshot(s, file);
        String after = bot.client(() -> Frozen.fingerprint(mc));
        s.info("state " + file, state + (after.equals(state) ? "" : "   | AFTER: " + after));
        bot.client(Frozen::endCapture);
        s.check(path != null, "scene " + sc.packageId() + "/" + sc.id() + " captured as " + file + ".png (" + sc.tolerance() + ")", "no capture file");
        if (path != null) {
            BufferedImage img = ImageStats.read(path);
            double luma = ImageStats.meanLuma(img);
            if (luma < 1.0 && !sc.option("black", "no").equals("ok")) {
                s.warn("scene " + sc.id(), "capture is black (mean luma " + String.format(Locale.ROOT, "%.2f", luma) + ")");
            }
        }
        return path;
    }

    /** Writes a mask image for a scene (white = masked) where {@link CaptureCompare} looks for it. */
    public static void writeMask(Path captureDir, String sceneId, BufferedImage mask) throws IOException {
        Path f = CaptureCompare.maskFile(captureDir, sceneId);
        Files.createDirectories(f.getParent());
        ImageIO.write(mask, "png", f.toFile());
    }
}
