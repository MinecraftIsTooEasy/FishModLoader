package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.nbt.NBTBase;
import net.minecraft.world.storage.WorldInfo;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinWorldInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

@Mixin(WorldInfo.class)
public class WorldInfoMixin implements IMixinWorldInfo {
    @Unique
    private Map<String, NBTBase> forge_additionalProperties;

    @Override
    public void setAdditionalProperties(Map<String, NBTBase> additionalProperties) {
        // one time set for this
        if (this.forge_additionalProperties == null) {
            this.forge_additionalProperties = additionalProperties;
        }
    }
}
