package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.nbt.NBTTagList;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(NBTTagList.class)
public class NBTTagListMixin {
    // Removes @SideOnly(Side.CLIENT) from removeTag method.
    // The method is already accessible from server. No action needed.
}
