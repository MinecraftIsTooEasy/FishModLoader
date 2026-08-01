package net.xiaoyu233.fml.reload.transform.client;

import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.util.UrlUtil;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.resources.ResourceManager;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.client.resources.SimpleResource;
import net.xiaoyu233.fml.FishModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Mixin(SimpleReloadableResourceManager.class)
public class SimpleResourceManagerMixin {
    /** MITE replaces the supplied pack list with only Default + MITE. Preserve
     * Forge/Fabric packs before that replacement so namespaced mod assets remain
     * available to TextureMap. */
    @ModifyVariable(method = "reloadResources(Ljava/util/List;)V", at = @At("HEAD"), argsOnly = true)
    private List preserveModResourcePacks(List packs) {
        return new ArrayList(packs) {
            @Override public void clear() {
                // Vanilla MITE clears this list before adding its two built-ins.
                // Keep caller-provided mod packs.
            }
            @Override public boolean add(Object pack) {
                return contains(pack) || super.add(pack);
            }
        };
    }

    @Redirect(method = "getAllResources", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/ResourceManager;getAllResources(Lnet/minecraft/util/ResourceLocation;)Ljava/util/List;"))
    private List enhanceGetAllResources(ResourceManager obj, ResourceLocation location){
        List allResources = obj.getAllResources(location);
        for (ModContainerImpl value : FishModLoader.getModsMap().values()) {
            MetadataSerializer metadataSerializer = new MetadataSerializer();
            try {
                InputStream resourceAsStream = UrlUtil.asUrl(value.getPath("assets/" + location.getResourceDomain() + "/" + location.getResourcePath())).openStream();
                InputStream metaStream = null;
                try {
                     metaStream = UrlUtil.asUrl(value.getPath("assets/" + location.getResourceDomain() + "/" + location.getResourcePath() + ".mcmeta")).openStream();
                }catch (Exception ignored){}
                if (resourceAsStream != null){
                    allResources.add(new SimpleResource(location, resourceAsStream, metaStream, metadataSerializer));
                }
            } catch (IOException ignored) {

            }
        }
        return allResources;
    }
}
