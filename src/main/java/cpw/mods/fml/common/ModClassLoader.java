/*
 * Forge Mod Loader
 * Copyright (c) 2012-2013 cpw.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     cpw - implementation
 */

package cpw.mods.fml.common;

import com.google.common.collect.ImmutableList;
import cpw.mods.fml.common.asm.transformers.AccessTransformer;
import cpw.mods.fml.common.asm.transformers.ModAPITransformer;
import cpw.mods.fml.common.discovery.ASMDataTable;
import cpw.mods.fml.common.modloader.BaseModProxy;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * A simple delegating class loader used to load mods into the system
 *
 *
 * @author cpw
 *
 */
public class ModClassLoader extends URLClassLoader
{
    private static final List<String> STANDARD_LIBRARIES = ImmutableList.of("jinput.jar", "lwjgl.jar", "lwjgl_util.jar");
    private Object mainClassLoader;

    public ModClassLoader(ClassLoader parent) {
        super(new URL[0], null);
        if (parent instanceof LaunchClassLoader) {
            this.mainClassLoader = parent;
        } else {
            // FishModLoader KnotClassLoader — store as generic ClassLoader
            this.mainClassLoader = parent;
        }
    }

    public void addFile(File modFile) throws MalformedURLException
    {
        URL url = modFile.toURI().toURL();
        if (mainClassLoader instanceof LaunchClassLoader) {
            ((LaunchClassLoader) mainClassLoader).addURL(url);
        } else {
            try {
                Method addUrl = mainClassLoader.getClass().getMethod("addURL", URL.class);
                addUrl.invoke(mainClassLoader, url);
            } catch (Exception e) {
                FMLLog.log(Level.WARNING, e, "Failed to add URL to classloader");
            }
        }
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException
    {
        if (mainClassLoader instanceof ClassLoader) {
            return ((ClassLoader) mainClassLoader).loadClass(name);
        }
        return super.loadClass(name);
    }

    public File[] getParentSources() {
        List<URL> urls;
        if (mainClassLoader instanceof LaunchClassLoader) {
            urls = ((LaunchClassLoader) mainClassLoader).getSources();
        } else {
            // KnotClassLoader doesn't expose getSources — return empty
            return new File[0];
        }
        File[] sources = new File[urls.size()];
        try
        {
            for (int i = 0; i<urls.size(); i++)
            {
                sources[i] = new File(urls.get(i).toURI());
            }
            return sources;
        }
        catch (URISyntaxException e)
        {
            FMLLog.log(Level.SEVERE, e, "Unable to process our input to locate the minecraft code");
            throw new LoaderException(e);
        }
    }

    public List<String> getDefaultLibraries()
    {
        return STANDARD_LIBRARIES;
    }

    public Class<? extends BaseModProxy> loadBaseModClass(String modClazzName) throws Exception
    {
        AccessTransformer accessTransformer = null;
        if (mainClassLoader instanceof LaunchClassLoader) {
            for (IClassTransformer transformer : ((LaunchClassLoader) mainClassLoader).getTransformers())
            {
                if (transformer instanceof AccessTransformer)
                {
                    accessTransformer = (AccessTransformer) transformer;
                    break;
                }
            }
        }
        if (accessTransformer == null)
        {
            FMLLog.log(Level.SEVERE, "No access transformer found");
            throw new LoaderException();
        }
        accessTransformer.ensurePublicAccessFor(modClazzName);
        return (Class<? extends BaseModProxy>) Class.forName(modClazzName, true, this);
    }

    public void clearNegativeCacheFor(Set<String> classList)
    {
        if (mainClassLoader instanceof LaunchClassLoader) {
            ((LaunchClassLoader) mainClassLoader).clearNegativeEntries(classList);
        }
    }

    public ModAPITransformer addModAPITransformer(ASMDataTable dataTable)
    {
        if (!(mainClassLoader instanceof LaunchClassLoader)) {
            FMLLog.log(Level.WARNING, "Cannot register ModAPITransformer without a LaunchClassLoader");
            return null;
        }
        LaunchClassLoader lcl = (LaunchClassLoader) mainClassLoader;
        lcl.registerTransformer("cpw.mods.fml.common.asm.transformers.ModAPITransformer");
        List<IClassTransformer> transformers = lcl.getTransformers();
        ModAPITransformer modAPI = (ModAPITransformer) transformers.get(transformers.size()-1);
        modAPI.initTable(dataTable);
        return modAPI;
    }
}
