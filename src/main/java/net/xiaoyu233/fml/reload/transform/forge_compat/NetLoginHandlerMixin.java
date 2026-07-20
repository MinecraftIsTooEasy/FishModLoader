package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetLoginHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.TcpConnection;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinNetLoginHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NetLoginHandler.class)
public class NetLoginHandlerMixin implements IMixinNetLoginHandler {
    @Shadow private MinecraftServer mcServer;
    @Shadow private TcpConnection myTCPConnection;
    @Shadow private String clientUsername;
    @Shadow private boolean connectionComplete;

    @Shadow private void raiseErrorAndDisconnect(String var1) {}

    @Override
    public void completeConnection(String var1) {
        if (var1 != null) {
            this.raiseErrorAndDisconnect(var1);
        } else {
            EntityPlayerMP var2 = this.mcServer.getConfigurationManager().createPlayerForUser(this.clientUsername);
            if (var2 != null) {
                this.mcServer.getConfigurationManager().initializeConnectionToPlayer(this.myTCPConnection, var2);
            }
        }
        this.connectionComplete = true;
    }

    @Override
    public EntityPlayer getPlayer() {
        return null;
    }
}
