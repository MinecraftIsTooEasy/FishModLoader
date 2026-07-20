package net.xiaoyu233.fml.reload.transform.forge_compat;

import cpw.mods.fml.client.GuiModList;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.xiaoyu233.fml.util.ReflectHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiMainMenu.class)
public abstract class GuiMainMenuMixin extends GuiScreen {
	@Unique private GuiButton fmlModButton = null;

	@Inject(method = "addSingleplayerMultiplayerButtons", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 1, shift = At.Shift.AFTER))
	private void addForgeButton(int par1, int par2, CallbackInfo ci) {
		fmlModButton = new GuiButton(100, this.width / 2 - 100, par1 + par2 * 2, "Mods");
		this.buttonList.add(fmlModButton);
	}

	@Inject(method = "func_130022_h", at = @At("TAIL"))
	private void modifyForgeButton(CallbackInfo ci) {
		fmlModButton.xPosition = 98;
		fmlModButton.yPosition = this.width / 2 + 2;
	}

	@Inject(method = "actionPerformed", at = @At("HEAD"))
	private void action(GuiButton button, CallbackInfo ci) {
		if (button.id == 100) {
			this.mc.displayGuiScreen(new GuiModList(ReflectHelper.dyCast(this)));
		}
	}
}
