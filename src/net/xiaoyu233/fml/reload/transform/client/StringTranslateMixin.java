package net.xiaoyu233.fml.reload.transform.client;

import net.minecraft.StringTranslate;
import net.xiaoyu233.fml.ModResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import java.util.Iterator;

@Mixin(StringTranslate.class)
public class StringTranslateMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(stringValue = "/assets/minecraft/lang/"))
    private String modifyResourceDomain(String constant) {
        Iterator defaultResourceDomains = ModResourceManager.resourceDomains.iterator();
        String defaultResourceDomainsString = "minecraft";
        while (defaultResourceDomains.hasNext()) {
            defaultResourceDomainsString = (String) defaultResourceDomains.next();
        }
        return "/assets/" + defaultResourceDomainsString + "/lang/";
    }
}
