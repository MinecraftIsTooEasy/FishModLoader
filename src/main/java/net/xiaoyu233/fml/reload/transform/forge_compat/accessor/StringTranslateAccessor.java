package net.xiaoyu233.fml.reload.transform.forge_compat.accessor;

import net.minecraft.util.StringTranslate;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IStringTranslateAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(StringTranslate.class)
public abstract class StringTranslateAccessor implements IStringTranslateAccessor {
    @Accessor("languageList")
    public abstract Map<String, String> getLanguageList();
}
