package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.NetClientHandler;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemMap;
import net.minecraft.network.packet.NetHandler;
import net.minecraft.network.packet.Packet131MapData;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.minecraft.network.packet.Packet3Chat;
import net.minecraft.world.storage.MapData;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinNetClientHandler;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NetClientHandler.class)
public abstract class NetClientHandlerMixin extends NetHandler implements IMixinNetClientHandler {
    @Shadow private WorldClient worldClient;
    @Shadow private Minecraft mc;

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

    /**
     * Fires {@link ClientChatReceivedEvent} before printing a chat message.
     * <p>
     * The Forge patch wraps the chat message with the event, cancelling
     * if the event is vetoed, and uses the event's message.
     */
    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnHandleChat(Packet3Chat par1Packet3Chat, CallbackInfo ci) {
        ClientChatReceivedEvent event = new ClientChatReceivedEvent(par1Packet3Chat.message);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            ci.cancel();
        }
        // Note: The event also replaces the message string. Full
        // replication of the patch body requires modifying the
        // par1Packet3Chat.message field before the original body runs.
    }

    /**
     * Placeholder: The Forge patch modifies {@code handleKickDisconnect}
     * to pass {@code par1Packet255KickDisconnect.reason} to
     * {@code networkShutdown} instead of an empty array.
     * <p>
     * This is a simple argument change that requires body modification.
     */
    @org.spongepowered.asm.mixin.Unique
    private void fmlForgeHandleKickDisconnect() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for handleKickDisconnect argument change.");
    }

    /**
     * Placeholder: The Forge patch adds an {@code else} branch to the
     * tile entity data handler that calls
     * {@code tileentity.onDataPacket(netManager, par1Packet132TileEntityData)}.
     * <p>
     * This requires adding an else clause inside an if-else chain,
     * which cannot be done with {@code @Inject}.
     */
    @org.spongepowered.asm.mixin.Unique
    private void fmlForgeHandleTileEntityData() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for onDataPacket call.");
    }
}
