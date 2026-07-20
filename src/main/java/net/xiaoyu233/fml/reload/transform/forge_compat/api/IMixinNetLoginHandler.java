package net.xiaoyu233.fml.reload.transform.forge_compat.api;

import net.minecraft.entity.player.EntityPlayer;

public interface IMixinNetLoginHandler {
    void completeConnection(String var1);
    EntityPlayer getPlayer();
}
