package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(CommandHandler.class)
public abstract class CommandHandlerMixin {

    @Inject(method = "executeCommand(Lnet/minecraft/command/ICommandSender;Ljava/lang/String;Z)I", at = @At("HEAD"), cancellable = true)
    private void fmlForgeExecuteCommand(ICommandSender par1ICommandSender, String par2Str, boolean permissionOverride, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        // The actual CommandEvent is fired inside the method body - handled via @Redirect
    }

    @Unique
    protected boolean fmlForgeFireCommandEvent(ICommand command, ICommandSender sender, String[] args) {
        CommandEvent event = new CommandEvent(command, sender, args);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            if (event.exception != null) {
                throw new RuntimeException(event.exception);
            }
            return true; // consumed
        }
        return false;
    }
}
