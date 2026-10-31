package net.xiaoyu233.fml.relaunch.client;

import net.fabricmc.loader.impl.discovery.ModResolutionException;
import net.xiaoyu233.fml.relaunch.JvmPresets;
import net.xiaoyu233.fml.relaunch.LaunchDelegate;
import net.xiaoyu233.fml.relaunch.MacFirstThread;
import net.xiaoyu233.fml.relaunch.NativeExtractor;
import net.xiaoyu233.fml.relaunch.VulkanConfigLoader;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class Main {
   public static void main(String[] args) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, ModResolutionException {
      System.setProperty("file.encoding", "UTF-8");

      Path gameDir = findGameDir(args);
      System.setProperty("fml.gameDir", gameDir.toString());

      // config/fml-vulkan.properties -> fml.vulkan.* system properties (keys already set on the command line win)
      VulkanConfigLoader.loadConfig(gameDir);

      if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
         // GLFW needs the first thread: restarts the JVM with -XstartOnFirstThread when required and exits with its exit code.
         // Done before anything else so that the restarting parent does no work (natives, class loading) that the child repeats
         MacFirstThread.ensureFirstThread(args);

         if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
         }
      }

      // -Dfml.jvm.preset / FML_JVM_PRESET: extra JVM flags (restarts the JVM once when the preset adds flags; default adds none)
      JvmPresets.apply(args);

      // LWJGL 3 natives from the FML jar -> <gameDir>/.fml/natives (sets org.lwjgl.librarypath, fml.natives.dir, Vulkan loader)
      NativeExtractor.extractNatives(gameDir);

      LaunchDelegate.delegateLaunch("net.minecraft.client.main.Main", args, false);
   }

   /** The --gameDir argument (also as --gameDir=path) or the working directory; absolute and normalized. */
   private static Path findGameDir(String[] args) {
      String gameDir = null;
      for (int i = 0; i < args.length; i++) {
         if (args[i].equals("--gameDir") && i + 1 < args.length) {
            gameDir = args[i + 1];
            break;
         }
         if (args[i].startsWith("--gameDir=")) {
            gameDir = args[i].substring("--gameDir=".length());
            break;
         }
      }
      return Paths.get(gameDir != null ? gameDir : System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
   }
}
