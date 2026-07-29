package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CreativeTabs.class)
public abstract class CreativeTabsMixin {

    @Shadow
    @Final
    private int tabIndex;

    @Shadow
    @Final
    private static CreativeTabs[] creativeTabArray;

    @Shadow
    public abstract Item getTabIconItem();

    @Shadow
    @Final
    private static CreativeTabs tabAllSearch;

    @Unique
    public int getTabPage() {
        if (tabIndex > 11) {
            return ((tabIndex - 12) / 10) + 1;
        }
        return 0;
    }

    @Unique
    public int getTabIndex() {
        return this.tabIndex;
    }

    @Unique
    private static int getNextID() {
        return creativeTabArray.length;
    }

    @Unique
    public ItemStack getIconItemStack() {
        return new ItemStack(getTabIconItem());
    }

    @Unique
    public boolean hasSearchBar() {
        return this.tabIndex > 11;
    }
}
