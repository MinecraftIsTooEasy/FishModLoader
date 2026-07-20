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

package cpw.mods.fml.common.discovery;

import com.google.common.collect.Lists;
import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.LoaderException;
import cpw.mods.fml.common.MetadataCollection;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModContainerFactory;
import cpw.mods.fml.common.discovery.asm.ASMModParser;

import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;

public class JarDiscoverer implements ITypeDiscoverer
{
    @Override
    public List<ModContainer> discover(ModCandidate candidate, ASMDataTable table)
    {
        List<ModContainer> foundMods = Lists.newArrayList();
        FMLLog.fine("Examining file %s for potential mods", candidate.getModContainer().getName());
        JarFile jar = null;
        String[] ingoredNames = new String[]{"access-bridge-64.jar", "cldrdata.jar", "dnsns.jar", "jaccess.jar", "localedata.jar", "nashorn.jar", "sunec.jar",
                                "sunjce_provider.jar", "sunmscapi.jar", "sunpkcs11.jar", "zipfs.jar", "jce.jar", "jfr.jar", "jsse.jar", "management-agent.jar",
                                "resources.jar", "rt.jar", "codecwav-20101023.jar", "soundsystem-20120107.jar", "commons-lang3-3.1.jar", "cprov-jdk15on-1.47.jar",
                                "fastutil-8.5.12.jar", "jopt-simple-4.5.jar", "jinput-platform-2.0.5-natives-windows.jar", "lwjgl-2.9.0.jar", "lwjgl-platform-2.9.0-natives-windows.jar",
                                "lwjgl_util-2.9.0.jar", "argo-2.25_fixed.jar", "codecjorbis-20101023.jar", "jinput-2.0.5.jar", "commons-io-2.4.jar", "jutils-1.0.0.jar",
                                "gson-2.2.2.jar", "librarylwjglopenal-20100824.jar", "libraryjavasound-20101123.jar", "launchwrapper-1.12.jar", "lzma-0.0.1.jar",
                                "guava-14.0.jar", "asm-all-5.2.jar", "idea_rt.jar", "bcprov-jdk15on-1.47.jar", "charsets.jar"};
        for (String name : ingoredNames) {
            if (candidate.getModContainer().getName().equals(name)) {
                return foundMods;
            }
        }

        try
        {
            if (jar.getManifest()!=null && (jar.getManifest().getMainAttributes().get("FMLCorePlugin") != null || jar.getManifest().getMainAttributes().get("TweakClass") != null))
            {
                FMLLog.finest("Ignoring coremod or tweak system %s", candidate.getModContainer());
                return foundMods;
            }

            ZipEntry modInfo = jar.getEntry("mcmod.info");
            MetadataCollection mc = null;
            if (modInfo != null)
            {
                FMLLog.finer("Located mcmod.info file in file %s", candidate.getModContainer().getName());
                mc = MetadataCollection.from(jar.getInputStream(modInfo), candidate.getModContainer().getName());
            }
            else
            {
                FMLLog.fine("The mod container %s appears to be missing an mcmod.info file", candidate.getModContainer().getName());
                mc = MetadataCollection.from(null, "");
            }
            for (ZipEntry ze : Collections.list(jar.entries()))
            {
                if (ze.getName()!=null && ze.getName().startsWith("__MACOSX"))
                {
                    continue;
                }
                Matcher match = classFile.matcher(ze.getName());
                if (match.matches())
                {
                    ASMModParser modParser;
                    try
                    {
                        modParser = new ASMModParser(jar.getInputStream(ze));
                        candidate.addClassEntry(ze.getName());
                    }
                    catch (LoaderException e)
                    {
                        FMLLog.log(Level.SEVERE, e, "There was a problem reading the entry %s in the jar %s - probably a corrupt zip", ze.getName(), candidate.getModContainer().getPath());
                        jar.close();
                        throw e;
                    }
                    modParser.validate();
                    modParser.sendToTable(table, candidate);
                    ModContainer container = ModContainerFactory.instance().build(modParser, candidate.getModContainer(), candidate);
                    if (container!=null)
                    {
                        table.addContainer(container);
                        foundMods.add(container);
                        container.bindMetadata(mc);
                    }
                }
            }
        }
        catch (Exception e)
        {
            FMLLog.log(Level.WARNING, e, "Zip file %s failed to read properly, it will be ignored", candidate.getModContainer().getName());
        }
        finally
        {
            if (jar != null)
            {
                try
                {
                    jar.close();
                }
                catch (Exception e)
                {
                }
            }
        }
        return foundMods;
    }

}
