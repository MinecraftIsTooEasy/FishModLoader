package net.xiaoyu233.fml.reload.transform.id_extend;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemMap;
import net.minecraft.world.storage.MapData;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ItemMap.class)
public class ItemMapMixin {
    @ModifyConstant(method = "updateMapData", constant = @Constant(intValue = 256))
    private int mapFix(int value) {
        return 4096;
    }
}
