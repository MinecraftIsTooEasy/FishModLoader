package net.xiaoyu233.fml.vulkan.window;

import org.lwjgl.Sys;

import java.io.IOException;
import java.net.URI;

/**
 * Stand-in for {@code java.awt.Desktop}: the game opens links with {@code Class.forName("java.awt.Desktop")} + reflection
 * ({@code getDesktop()} / {@code browse(URI)}), which fails when AWT is headless (macOS: AWT and GLFW both want the AppKit
 * main thread, so FML runs headless). The link mixins in {@code net.xiaoyu233.fml.vulkan.mixin.window} swap the class name
 * for this class, which opens the URL through the system ({@link Sys#openURL}).
 *
 * <p>Not in the mixin package on purpose: the game loads it by name through reflection.
 */
public final class DesktopShim {
    public static final String AWT_DESKTOP = "java.awt.Desktop";
    public static final String CLASS_NAME = "net.xiaoyu233.fml.vulkan.window.DesktopShim";

    private static final DesktopShim INSTANCE = new DesktopShim();

    private DesktopShim() {
    }

    /** What the link mixins use as the argument of Class.forName: the shim instead of java.awt.Desktop, everything else unchanged. */
    public static String redirect(String className) {
        return AWT_DESKTOP.equals(className) ? CLASS_NAME : className;
    }

    /** Reflection entry point: {@code Desktop.getDesktop()}. */
    public static DesktopShim getDesktop() {
        return INSTANCE;
    }

    /** Reflection entry point: {@code Desktop.browse(URI)}. */
    public void browse(URI uri) throws IOException {
        if (uri == null || !Sys.openURL(uri.toString())) {
            throw new IOException("Could not open " + uri);
        }
    }
}
