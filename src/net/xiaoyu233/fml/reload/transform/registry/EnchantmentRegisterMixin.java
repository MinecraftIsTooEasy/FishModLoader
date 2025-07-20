package net.xiaoyu233.fml.reload.transform.registry;

import net.minecraft.Enchantment;
import net.xiaoyu233.fml.reload.event.EnchantmentRegistryEvent;
import net.xiaoyu233.fml.reload.event.MITEEvents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.Enchantment.enchantmentsList;

@Mixin(Enchantment.class)
public class EnchantmentRegisterMixin {
    @Shadow @Final @Mutable
    public static Enchantment[] enchantmentsBookList;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void injectRegister(CallbackInfo callbackInfo) {
        EnchantmentRegistryEvent event = new EnchantmentRegistryEvent();
        MITEEvents.MITE_EVENT_BUS.post(event);
        registerDynamicEnchantments(event.getEnchantmentList());
    }

    private static synchronized void registerDynamicEnchantments(List<Enchantment> newEnchantments) {
        if (newEnchantments == null || newEnchantments.isEmpty()) {
            return;
        }
        List<Enchantment> filtered = new ArrayList<>(enchantmentsList.length);
        for (Enchantment enchantment : enchantmentsList) {
            if (enchantment != null) {
                filtered.add(enchantment);
            }
        }
        enchantmentsBookList = filtered.toArray(new Enchantment[0]);
    }
}
