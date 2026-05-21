package net.xiaoyu233.fml.mixin.service;

import net.xiaoyu233.fml.relaunch.Launch;
import org.spongepowered.asm.service.IClassProvider;

import java.net.URL;
import java.net.URLClassLoader;

public class ClassProvider implements IClassProvider {
   ClassProvider() {
   }

   public static URL[] getSystemClassPathURLs() {
      ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
      if (systemClassLoader instanceof URLClassLoader) {
         return ((URLClassLoader) systemClassLoader).getURLs();
      }
      return new URL[0];
   }


   /** @deprecated */
   @Deprecated
   public URL[] getClassPath() {
      return getSystemClassPathURLs();
   }

   public Class<?> findClass(String name) throws ClassNotFoundException {
      return Class.forName(name, true, Launch.knotLoader.getClassLoader());
   }

   public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
      return Class.forName(name, initialize, Launch.knotLoader.getClassLoader());
   }

   public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
      return Class.forName(name, initialize, Launch.knotLoader.getClassLoader());
   }
}
