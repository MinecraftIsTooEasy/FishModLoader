package net.xiaoyu233.fml.reload.event;

import net.minecraft.EntityPlayer;
import net.minecraft.ICommandSender;
import net.minecraft.World;

public class HandleChatCommandEvent {
   private final String command;
   private final ICommandSender listener;
   private final EntityPlayer player;
   private final World world;
   private boolean executeSuccess = false;

   public HandleChatCommandEvent(ICommandSender par1ICommandSender, String par2Str, EntityPlayer player, World world) {
      this.listener = par1ICommandSender;
      this.command = par2Str;
      this.player = player;
      this.world = world;
   }

   public ICommandSender getListener() {
      return this.listener;
   }

   public World getWorld() {
      return this.world;
   }

   public String getCommand() {
      return this.command;
   }

   public EntityPlayer getPlayer() {
      return this.player;
   }

   public void setExecuteSuccess(boolean executeSuccess) {
      this.executeSuccess = executeSuccess;
   }

   public boolean isExecuteSuccess() {
      return this.executeSuccess;
   }
}
