package net.xiaoyu233.fml.reload.transform.forge_compat.api;

import net.minecraft.network.packet.Packet131MapData;

public interface IMixinNetClientHandler extends IMixinNetHandler {
    void fmlPacket131Callback(Packet131MapData mapData);
}
