package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.NetClientHandler;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemMap;
import net.minecraft.network.packet.Packet131MapData;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.minecraft.world.storage.MapData;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinNetClientHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NetClientHandler.class)
public abstract class NetClientHandlerMixin extends NetHandlerMixin implements IMixinNetClientHandler {
    @Shadow private Minecraft mc;
    @Shadow private WorldClient worldClient;

    @Override
    public void fmlPacket131Callback(Packet131MapData mapData) {
        if (mapData.itemID == Item.map.itemID) {
            MapData mapdata = ItemMap.getMPMapData(mapData.uniqueID, this.mc.theWorld);
            mapdata.updateMPMapData(mapData.itemData);
        } else {
            this.mc.getLogAgent().logWarning("Unknown itemid: " + mapData.uniqueID);
        }
    }

    @Override
    public void handleVanilla250Packet(Packet250CustomPayload payload) {
        // Implements the forge method for handling vanilla custom payloads
    }

    @Override
    public EntityPlayer getPlayer() {
        return this.mc.thePlayer;
    }
}
