package net.xiaoyu233.fml.reload.transform.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.DefaultResourcePack;
import net.minecraft.ResourceLocation;
import net.xiaoyu233.fml.ModResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

@Mixin(DefaultResourcePack.class)
public abstract class DefaultResourcePackMixin {
    @Shadow
    @Final
    private File fileAssets;

//    @Redirect(method = "getPackMetadata", at = @At(value = "INVOKE", target = "Ljava/lang/Class;getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"))
//    private InputStream redirectGetPackMetadata(Class<?> c, String path){
//        return DefaultResourcePack.class.getClassLoader().findResources(path);
//    }


    @Shadow
    @Final
    private Map mapResourceFiles;

//    @Redirect(method = "getInputStream", at = @At(value = "INVOKE", target = "Lnet/minecraft/DefaultResourcePack;getResourceStream(Lnet/minecraft/ResourceLocation;)Ljava/io/InputStream;"))
//    private InputStream redirectFixLanguageLoad(DefaultResourcePack t, ResourceLocation resourceLocation) {
//        String resourcePath = resourceLocation.getResourcePath();
//        if (this.fileAssets.toString().contains("\\legacy") && resourcePath.contains(".lang") && !resourcePath.contains("en_US.lang")) {
//            return null;
//        } else {
//            return this.getResourceStream(resourceLocation);
//        }
//    }

    @WrapOperation(
            method = "getInputStream",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/DefaultResourcePack;getResourceStream(Lnet/minecraft/ResourceLocation;)Ljava/io/InputStream;"
            )
    )
    private InputStream wrapFixLanguageLoad(DefaultResourcePack instance, ResourceLocation resourceLocation, Operation<InputStream> original) {
        String resourceDomain = resourceLocation.getResourceDomain();
        String resourcePath = resourceLocation.getResourcePath();
        if (resourcePath.contains(".lang") && (resourceDomain.equals("minecraft") || resourceDomain.equals("legacy") ||
                resourceDomain.equals("objects") || resourceDomain.equals("virtual") || resourceDomain.equals("indexes"))) {
            if (!resourcePath.contains("en_US")) {
                return null;
            }
        }// deal with vanilla lang reading, it works though idk why
        return original.call(instance, resourceLocation);
    }

//    @Overwrite
//    public InputStream getInputStream(ResourceLocation location) throws FileNotFoundException {
//        InputStream inputstream = this.getResourceStream(location);
//        if (inputstream != null) {
//            return inputstream;
//        } else {
//            File inputstream1 = (File) this.mapResourceFiles.get(location.toString());
//            if (inputstream1 != null) {
//                return new FileInputStream(inputstream1);
//            } else {
//                throw new FileNotFoundException(location.getResourcePath());
//            }
//        }
//    }
//
//    @Overwrite
//    public InputStream getInputStream(ResourceLocation location) throws IOException {
//        InputStream inputstream = this.getResourceStream(location);
//        if (inputstream != null) {
//            return inputstream;
//        } else {
//            InputStream inputstream1 = this.getInputStreamAssets(location);
//            if (inputstream1 != null) {
//                return inputstream1;
//            } else {
//                throw new FileNotFoundException(location.getResourcePath());
//            }
//        }
//    }
//
//    public InputStream getInputStreamAssets(ResourceLocation location) throws IOException, FileNotFoundException {
//        File file1 = (File) this.mapResourceFiles.get(location.toString());
//        return file1 != null && file1.isFile() ? new FileInputStream(file1) : null;
//    }

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void addDefaultResourceDomain(CallbackInfo ci) {
        ModResourceManager.resourceDomains.add("minecraft");
    }

    @Overwrite
    private InputStream getResourceStream(ResourceLocation resourceLocation) {
        return DefaultResourcePack.class.getResourceAsStream("/assets/" + resourceLocation.getResourceDomain() + "/" + resourceLocation.getResourcePath());
    }

    @Overwrite
    public Set<?> getResourceDomains() {
        return ModResourceManager.resourceDomains;
    }
}
