package net.minecraftforge.common;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;

import java.io.File;

/**
 * STUB. Forwards to the wrapped parent SaveHandler. The original delegates
 * to ChunkSaveLocation/data folders that aren't accessible on stock MITE
 * without AT/Mixin. Stage 6 will provide the real per-dimension save path.
 */
public class WorldSpecificSaveHandler implements ISaveHandler {
    private final WorldServer world;
    private final ISaveHandler parent;

    public WorldSpecificSaveHandler(WorldServer world, ISaveHandler parent) {
        this.world = world;
        this.parent = parent;
    }

    @Override public WorldInfo loadWorldInfo() { return parent.loadWorldInfo(); }
    @Override public void checkSessionLock() { parent.checkSessionLock(); }
    @Override public IChunkLoader getChunkLoader(WorldProvider provider) { return parent.getChunkLoader(provider); }
    @Override public void saveWorldInfoWithPlayer(WorldInfo info, NBTTagCompound playerData) { parent.saveWorldInfoWithPlayer(info, playerData); }
    @Override public void saveWorldInfo(WorldInfo info) { parent.saveWorldInfo(info); }
    @Override public IPlayerFileData getSaveHandler() { return parent.getSaveHandler(); }
    @Override public void flush() { parent.flush(); }
    @Override public File getMapFileFromName(String mapName) { return parent.getMapFileFromName(mapName); }
    @Override public String getWorldDirectoryName() { return parent.getWorldDirectoryName(); }
}
