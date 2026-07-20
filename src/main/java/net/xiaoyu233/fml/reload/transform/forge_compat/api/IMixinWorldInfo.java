package net.xiaoyu233.fml.reload.transform.forge_compat.api;

import net.minecraft.nbt.NBTBase;

import java.util.Map;

public interface IMixinWorldInfo {
    void setAdditionalProperties(Map<String, NBTBase> additionalProperties);
}
