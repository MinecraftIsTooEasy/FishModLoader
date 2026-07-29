package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetLoginHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.TcpConnection;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinNetLoginHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

@Mixin(NetLoginHandler.class)
public class NetLoginHandlerMixin implements IMixinNetLoginHandler {
    @Shadow @Final private MinecraftServer mcServer;
    @Shadow @Final public TcpConnection myTCPConnection;
    @Shadow private String clientUsername;
    @Shadow public boolean finishedProcessing;

    @Shadow public void kickUser(String reason) {}

    @Override
    public void completeConnection(String errorMessage) {
        if (errorMessage != null) {
            this.kickUser(errorMessage);
            this.finishedProcessing = true;
        } else {
            // MITE's initializePlayerConnection already does createPlayerForUser
            // + initializeConnectionToPlayer + set finishedProcessing.
            ((NetLoginHandler)(Object)this).initializePlayerConnection();
        }
    }

    @Override
    public EntityPlayer getPlayer() {
        return null;
    }
}
