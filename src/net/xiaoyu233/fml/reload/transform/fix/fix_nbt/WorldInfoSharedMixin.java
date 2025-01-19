package net.xiaoyu233.fml.reload.transform.fix.fix_nbt;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.WorldAchievement;
import net.minecraft.WorldInfoShared;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashMap;

@Mixin(WorldInfoShared.class)
public class WorldInfoSharedMixin {
    @WrapWithCondition(method = "<init>(Lnet/minecraft/NBTTagCompound;)V", at = @At(value = "INVOKE", target = "Ljava/util/HashMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private <K, V> boolean wrap(HashMap<?, ?> instance, K key, V value) {
        WorldAchievement achievement = (WorldAchievement) value;
        return achievement.achievement != null;
    }
}
