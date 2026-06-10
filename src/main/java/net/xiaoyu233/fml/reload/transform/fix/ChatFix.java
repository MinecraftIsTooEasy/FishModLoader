package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiScreen.class)
public abstract class ChatFix extends Gui {
   @Redirect(method = "handleKeyboardInput", at = @At(value = "INVOKE",target = "Lorg/lwjgl/input/Keyboard;getEventKeyState()Z"))
   private boolean redirectAllowCharInput(){
      int k = Keyboard.getEventKey();
      char c = Keyboard.getEventCharacter();
      return Keyboard.getEventKeyState() || k == 0 && Character.isDefined(c);
   }
}
