package net.xiaoyu233.fml.reload.transform.forge_compat.api;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.packet.Packet250CustomPayload;

public interface IMixinNetHandler {
    void handleVanilla250Packet(Packet250CustomPayload payload);
    EntityPlayer getPlayer();
}
