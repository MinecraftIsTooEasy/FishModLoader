package net.xiaoyu233.fml.reload.transform;

import com.google.gson.*;
import net.minecraft.*;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.Translations;
import net.xiaoyu233.fml.reload.event.LanguageResourceReloadEvent;
import net.xiaoyu233.fml.reload.event.MITEEvents;
import net.xiaoyu233.fml.reload.utils.LocaleDataFixer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Mixin(Locale.class)
public class LanguageLoaderTrans {
   @Shadow
   Map field_135032_a;

   @Inject(method = "loadLocaleDataFiles", at = @At(value = "INVOKE_ASSIGN", target = "Ljava/lang/String;format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", shift = At.Shift.AFTER), locals = LocalCapture.CAPTURE_FAILHARD)
   public synchronized void a(ResourceManager var1, List var2, CallbackInfo callbackInfo, Iterator<?>iterator, String var4, String var5) {
//      this.a.clear();
//
//      for (Object o : var2) {
//           String var4 = (String) o;
//           String var5 = String.format("lang/%s.lang", var4);
           MITEEvents.MITE_EVENT_BUS.post(new LanguageResourceReloadEvent(this.field_135032_a, var4));
           Translations.addTranslationsFor(this.field_135032_a, var4);
//
//           for (Object value : var1.a()) {
//               String var7 = (String) value;
//
//               try {
//                   this.a(var1.b(new bjo(var7, var5)));
//               } catch (IOException ignored) {
//               }
//           }
//       }
//
//      this.b();
   }

    @Inject(method = "translateKeyPrivate", at = @At(value = "HEAD"), cancellable = true)
    private void betterTranslation(String registerName, CallbackInfoReturnable<String> cir) {
        String localeTranslation = (String) this.field_135032_a.get(registerName);
        if (localeTranslation != null) {
            cir.setReturnValue(localeTranslation);
        } else {
            String statTranslation = StatCollector.translateToLocal(registerName);
            cir.setReturnValue((statTranslation != null ? statTranslation : registerName));
        }
    }

    @Inject(method = "loadLocaleDataFiles", at = @At(value = "INVOKE", target = "Lnet/minecraft/Locale;checkUnicode()V"))
    private void readJsonFile(ResourceManager resourceManager, List langList, CallbackInfo ci) {
        for (Object localeName : langList) {
            String legacyName = (String)localeName;
            this.loadJsonFile(resourceManager, String.format("lang/%s.json", legacyName));
            String futureName = LocaleDataFixer.translateToFuture(legacyName);
            if (futureName.equals(legacyName)) continue;
            this.loadJsonFile(resourceManager, String.format("lang/%s.json", futureName));
        }
    }

    @Unique
    private void loadJsonFile(ResourceManager resourceManager, String fileName) {
        for (Object resourceDomain : resourceManager.getResourceDomains()) {
            try {
                this.loadJsonData(resourceManager.getAllResources(new ResourceLocation((String)resourceDomain, fileName)), fileName);
            }
            catch (Exception exception) {}
        }
    }

    @Unique
    private void loadJsonData(List<Resource> list, String fileName) {
        for (Resource resource : list) {
            this.loadJsonData(resource.getInputStream(), fileName);
        }
    }

    @Unique
    private void loadJsonData(InputStream par1, String fileName) {
        InputStreamReader reader = new InputStreamReader(par1, StandardCharsets.UTF_8);
        try {
            JsonElement parse = new JsonParser().parse(reader);
            if (parse.isJsonObject()) {
                JsonObject jsonObject = parse.getAsJsonObject();
                jsonObject.entrySet().forEach(x -> this.field_135032_a.put(x.getKey(), (x.getValue()).getAsString()));
            }
        }
        catch (JsonIOException | JsonSyntaxException e) {
            FishModLoader.LOGGER.error("Exception when reading lang file: '{}'", fileName);
            e.printStackTrace();
        }
    }
}
