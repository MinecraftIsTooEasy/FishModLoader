package cpw.mods.fml.common;

import net.minecraft.nbt.NBTTagCompound;

import java.util.Map;

/**
 * Stub for cpw.mods.fml.common.WorldAccessContainer (Forge 1.6.4).
 * Provides hooks for mods to load/save data with a world. Real impl in stage 6.
 */
public interface WorldAccessContainer {
    NBTTagCompound getDataForWriting(net.minecraft.world.storage.SaveHandler handler, net.minecraft.world.storage.WorldInfo info);
    void readData(net.minecraft.world.storage.SaveHandler handler, net.minecraft.world.storage.WorldInfo info, Map<String, NBTTagCompound> propertyMap, NBTTagCompound tag);
}
