package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Forge compatibility for {@link ItemStack}.
 *
 * <p>Only the tooltip event is injected. Forge 1.6.4 patches a family of
 * {@code ItemStack} methods to delegate to the backing {@code Item} so that
 * mods can override stack limits, durability and enchantment glint per item.
 * MITE already does exactly that, verified against the remapped game jar:
 *
 * <pre>
 * getMaxStackSize()        -> Item.getItemStackLimit(subtype, damage)
 * isItemStackDamageable()  -> Item.isDamageable()      (null-safe)
 * getMaxDamage()           -> Item.getMaxDamage(ItemStack)
 * hasEffect()              -> Item.hasEffect(ItemStack)
 * getItemDamage()          -> reads the damage field directly
 * </pre>
 *
 * so re-implementing them here would add nothing.
 *
 * <p>This class previously carried nine {@code @Inject}s that each replaced one
 * of those methods at {@code HEAD} with a body that called the very same
 * {@code @Shadow} method, i.e. unconditional infinite recursion
 * ({@code getMaxStackSize} -> injector -> {@code getMaxStackSize}). Five were
 * directly self-recursive; the rest were redundant or wrong:
 *
 * <ul>
 *   <li>{@code setItemDamage} was shadowed as {@code void}, but MITE returns
 *       {@code ItemStack}, and its real implementation also drives
 *       {@code ItemAnvilBlock.updateSubtypeForDamage}, which an overwrite would
 *       have silently dropped.</li>
 *   <li>{@code canHarvestBlock(Block)} does not exist on MITE's
 *       {@code ItemStack} at all.</li>
 *   <li>{@code attemptDamageItem} bypassed MITE's own damage clamping.</li>
 * </ul>
 *
 * These predate this branch (they arrive with {@code f78f589}). They were never
 * reported by {@code tools/verify_overwrites.sh} because every shadowed name
 * does exist on MITE -- the defect is in the injected bodies and in one return
 * type, neither of which that script inspects.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "getTooltip(Lnet/minecraft/entity/player/EntityPlayer;ZLnet/minecraft/inventory/Slot;)Ljava/util/List;",
            at = @At("RETURN"))
    private void fmlForgeGetTooltip(EntityPlayer player, boolean advanced, Slot slot,
                                    CallbackInfoReturnable<List> cir) {
        List list = cir.getReturnValue();
        if (list != null) {
            ForgeEventFactory.onItemTooltip((ItemStack) (Object) this, player, list, advanced);
        }
    }
}
