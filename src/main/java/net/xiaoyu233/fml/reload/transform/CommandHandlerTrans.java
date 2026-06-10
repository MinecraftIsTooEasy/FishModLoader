package net.xiaoyu233.fml.reload.transform;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ChatMessageComponent;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.EnumCommand;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.config.editor.ConfigEditor;
import net.xiaoyu233.fml.reload.event.HandleChatCommandEvent;
import net.xiaoyu233.fml.reload.event.MITEEvents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Arrays;
import java.util.Map;

@Mixin(CommandHandler.class)
public class CommandHandlerTrans {
    @Shadow @Final private Map commandMap;

    @Inject(locals = LocalCapture.CAPTURE_FAILHARD ,method = "executeCommand",at = @At(value = "INVOKE_ASSIGN",shift = At.Shift.AFTER,target = "Lnet/minecraft/util/EnumCommand;get(Ljava/lang/String;)Lnet/minecraft/util/EnumCommand;"),cancellable = true)
    public void onCommandExecuted(ICommandSender par1ICommandSender, String par2Str,boolean permission_override, CallbackInfoReturnable<Integer> callbackInfo,MinecraftServer mc_server,WorldServer world, EntityPlayerMP player,EnumCommand command){
        String[] split = par2Str.split(" ");
        if (split.length > 0 && split[0].length() > 0) {
            ICommand registeredCommand = (ICommand) this.commandMap.get(split[0]);
            if (registeredCommand != null && (permission_override || registeredCommand.canCommandSenderUseCommand(par1ICommandSender))) {
                CommandEvent forgeEvent = new CommandEvent(registeredCommand, par1ICommandSender, Arrays.copyOfRange(split, 1, split.length));
                if (MinecraftForge.EVENT_BUS.post(forgeEvent)) {
                    callbackInfo.setReturnValue(1);
                    return;
                }
            }
        }

        HandleChatCommandEvent commandEvent = new HandleChatCommandEvent(par1ICommandSender,par2Str,player,world);
        MITEEvents.MITE_EVENT_BUS.post(commandEvent);
        if (commandEvent.isExecuteSuccess()){
            callbackInfo.setReturnValue(1);
        }
        if (par2Str.startsWith("configs reload")){
            if ((mc_server instanceof IntegratedServer && (mc_server.getConfigurationManager()
                    .getCurrentPlayerCount() > 1) &&
                    //Is remote player
                    !(Minecraft.getClientPlayer().getEntityName().equals(player.getEntityName())))
            ) {
                player.sendChatToPlayer(ChatMessageComponent.createFromText("你不是局域网主机,无法重载配置").setColor(EnumChatFormatting.RED));
                callbackInfo.setReturnValue(-1);
                return;
            }else if (mc_server.isDedicatedServer() &&
                    //Not server console
                    player != null){
                player.sendChatToPlayer(ChatMessageComponent.createFromText("你不是服务器控制台,无法重载配置").setColor(EnumChatFormatting.RED));
                callbackInfo.setReturnValue(-1);
            }else {
                mc_server.sendChatToPlayer(ChatMessageComponent.createFromText("[Server] 正在重载所有配置文件"));
                FishModLoader.reloadAllConfigs();
                callbackInfo.setReturnValue(1);
            }
        }
        if (par2Str.startsWith("configs edit")){
            if (mc_server instanceof IntegratedServer && (mc_server.getConfigurationManager()
                    .getCurrentPlayerCount() > 1) &&
                    //Is remote player
                    !(Minecraft.getClientPlayer().getEntityName().equals(player.getEntityName()))) {
                player.sendChatToPlayer(ChatMessageComponent.createFromText("你不是局域网主机,无法修改配置").setColor(EnumChatFormatting.RED));
            }else if (mc_server.isDedicatedServer() &&
                    //Not server console
                    player != null){
                player.sendChatToPlayer(ChatMessageComponent.createFromText("无法编辑服务器配置,请联系服主修改").setColor(EnumChatFormatting.RED));
            }else {
                if (player != null) {
                    player.sendChatToPlayer(ChatMessageComponent.createFromText("正在打开配置文件编辑器..."));
                } else {
                    System.out.println("正在打开配置文件编辑器...");
                }
                new ConfigEditor(FishModLoader.getAllConfigRegistries()).setVisible(true);
            }
            callbackInfo.setReturnValue(1);
        }
    }

//    public int executeCommand(ICommandListener par1ICommandSender, String par2Str, boolean permission_override) {}
}
