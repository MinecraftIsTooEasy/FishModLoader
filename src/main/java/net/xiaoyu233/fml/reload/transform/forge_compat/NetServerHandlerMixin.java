package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetServerHandler;
import net.minecraft.network.packet.NetHandler;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(NetServerHandler.class)
public abstract class NetServerHandlerMixin extends NetHandler {

    @Shadow
    private boolean hasMoved;

    @Shadow
    private double lastPosX;

    @Shadow
    private double lastPosY;

    @Shadow
    private double lastPosZ;

    @Shadow
    private EntityPlayerMP playerEntity;

    @Shadow
    private MinecraftServer mcServer;

    @Shadow
    private int teleportationId;

    @Shadow
    private int ticksForFloatKick;

    @Shadow
    public abstract void setPlayerLocation(double par1, double par3, double par5, float par7, float par8);

    @Unique
    private boolean fmlForgeHasMoved() {
        return hasMoved;
    }
}
