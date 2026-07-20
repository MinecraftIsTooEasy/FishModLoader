package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.packet.NetHandler;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinNetHandler;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(NetHandler.class)
public abstract class NetHandlerMixin implements IMixinNetHandler {
    @Override
    public void handleVanilla250Packet(Packet250CustomPayload payload) {
        // NOOP default - subclasses like NetClientHandler override this
    }

    @Override
    public EntityPlayer getPlayer() {
        return null;
    }
}
