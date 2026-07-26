package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.server.management.PlayerInstance;
import net.minecraft.world.ChunkCoordIntPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(PlayerInstance.class)
public abstract class PlayerInstanceMixin {

    @Shadow
    private List playersInChunk;

    @Shadow
    private ChunkCoordIntPair chunkLocation;

    @Shadow
    private short[] locationOfBlockChange;

    @Shadow
    private int numberOfTilesToUpdate;

    @Shadow
    private byte flagsYAreasToUpdate;

    @Shadow
    protected abstract void sendToAllPlayersWatchingChunk(net.minecraft.network.packet.Packet par1Packet);
}
