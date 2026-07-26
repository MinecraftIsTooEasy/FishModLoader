package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.nbt.CompressedStreamTools;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CompressedStreamTools.class)
public class CompressedStreamToolsMixin {
    // These patches remove @SideOnly(Side.CLIENT) annotations
    // from write(NBTTagCompound, File) and read(File) methods.
    // This is handled by Mixin since we're not re-declaring these methods.
}
