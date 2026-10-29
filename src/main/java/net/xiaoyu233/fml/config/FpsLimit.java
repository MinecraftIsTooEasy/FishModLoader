package net.xiaoyu233.fml.config;

/**
 * Resolves MITE's "Performance" option (GameSettings.limitFramerate: 1 balanced, 2 power saver, 3 enhanced; MITE maps 1 and 3 to 120 fps and 2 to 35 fps)
 * and the FML config key {@code Client.fpsCap} (fishmodloader.json) to a frame cap.
 *
 * <ul>
 *   <li>{@code fpsCap = 0} (default, "auto"): the game does not cap by itself. Only the "Power saver" option keeps its 35 fps cap (and the main menu its
 *       35 fps, as in MITE). The frame rate is then bounded by the in-game "Use VSync" option (on: display refresh rate) and nothing else.</li>
 *   <li>{@code fpsCap > 0}: fixed cap in fps for the whole game, whatever the Performance option says.</li>
 *   <li>{@code fpsCap < 0}: MITE original mapping (120 / 35 / 120).</li>
 * </ul>
 * Before 4.0.0-vulkan.2 the key was {@code fpsLimit} with default 120, which silently capped every run at 120 fps; that key is ignored now.
 */
public final class FpsLimit {
    /** Returned by {@link #cap(int)} for "no cap" (a value that is safe as a divisor and is recognised by the frame limiter). */
    public static final int UNLIMITED = 10000;

    private static volatile String source = "none";

    private FpsLimit() {
    }

    public static int cap(int mode) {
        int cfg = Configs.Client.FPS_LIMIT.get();
        if (cfg > 0) {
            source = "fml fpsCap " + cfg;
            return cfg;
        }
        if (cfg < 0) {
            int v = mode == 3 || mode == 1 ? 120 : mode == 2 ? 35 : 200;
            source = "MITE original " + v;
            return v;
        }
        if (mode == 2) {
            source = "game power saver 35";
            return 35;
        }
        source = "none";
        return UNLIMITED;
    }

    /** What decided the last {@link #cap(int)} result (for the F3 screen): "none", "game power saver 35", "fml fpsCap N", "MITE original N". */
    public static String lastSource() {
        return source;
    }
}
