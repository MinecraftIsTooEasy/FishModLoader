package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(FurnaceRecipes.class)
public class FurnaceRecipesMixin {
    @Shadow private Map smeltingList;

    @Unique
    private HashMap<List<Integer>, ItemStack> metaSmeltingList = new HashMap<List<Integer>, ItemStack>();
    @Unique
    private HashMap<List<Integer>, Float> metaExperience = new HashMap<List<Integer>, Float>();

    @Unique
    public void addSmelting(int itemID, int metadata, ItemStack itemstack, float experience) {
        metaSmeltingList.put(Arrays.asList(itemID, metadata), itemstack);
        metaExperience.put(Arrays.asList(itemstack.itemID, itemstack.getItemDamage()), experience);
    }

    @Unique
    public ItemStack getSmeltingResult(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemStack ret = (ItemStack) metaSmeltingList.get(Arrays.asList(item.itemID, item.getItemDamage()));
        if (ret != null) {
            return ret;
        }
        return (ItemStack) smeltingList.get(Integer.valueOf(item.itemID));
    }

    @Unique
    public float getExperience(ItemStack item) {
        if (item == null || item.getItem() == null) {
            return 0;
        }
        float ret = -1;
        if (ret < 0 && metaExperience.containsKey(Arrays.asList(item.itemID, item.getItemDamage()))) {
            ret = metaExperience.get(Arrays.asList(item.itemID, item.getItemDamage()));
        }
        if (ret < 0) {
            ret = item.getExperienceReward();
        }
        return (ret < 0 ? 0 : ret);
    }

    @Unique
    public Map<List<Integer>, ItemStack> getMetaSmeltingList() {
        return metaSmeltingList;
    }
}
