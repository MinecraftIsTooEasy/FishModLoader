package net.xiaoyu233.fml.reload.transform;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.reload.event.MITEEvents;
import net.xiaoyu233.fml.reload.event.PlayerLoggedInEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerTrans {
   @Shadow
   private static boolean treachery_detected;
   @Shadow
   private static int treachery_shutdown_counter;

   @Inject(method = "setTreacheryDetected", at = @At("HEAD"), cancellable = true)
   private static void removeTreacheryDetect(CallbackInfo info) {
      treachery_detected = false;
      treachery_shutdown_counter = 0;
      info.cancel();
   }

   @Inject(method = "playerLoggedIn", remap = false, at = @At("HEAD"), require = 1)
   private void onPlayerLoggedIn(CallbackInfo callbackInfo, @Local(argsOnly = true) ServerPlayer player) {
      MITEEvents.MITE_EVENT_BUS.post(new PlayerLoggedInEvent(player));
   }
}
