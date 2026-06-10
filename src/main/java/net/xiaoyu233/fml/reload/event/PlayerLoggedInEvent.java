package net.xiaoyu233.fml.reload.event;

import net.minecraft.entity.player.EntityPlayerMP;

public record PlayerLoggedInEvent(EntityPlayerMP player) {
}
