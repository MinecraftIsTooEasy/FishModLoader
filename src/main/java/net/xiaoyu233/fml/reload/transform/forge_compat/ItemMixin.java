package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.util.Icon;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.WeightedRandomChestContent;
import net.minecraft.world.World;
import net.minecraftforge.common.ChestGenHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(Item.class)
public abstract class ItemMixin {

    @Shadow @Final public int itemID;

    @Shadow public abstract boolean requiresMultipleRenderPasses();

    @Shadow public abstract boolean hasContainerItem();

    @Shadow public abstract Item getContainerItem();

    @Shadow public abstract CreativeTabs getCreativeTab();

    @Shadow public abstract boolean isPotionIngredient();

    @Shadow public abstract String getPotionEffect();

    @Shadow public abstract boolean hasEffect(ItemStack stack);

    @Unique
    protected boolean canRepair = true;

    @Unique
    public boolean onDroppedByPlayer(ItemStack item, EntityPlayer player) {
        return true;
    }

    @Unique
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ) {
        return false;
    }

    // getStrVsBlock(Block, int) / isRepairable() are NOT added here.
    // MITE declares both on Item itself, so Mixin discards any @Unique copy
    // ("already exists in net.minecraft.item.Item"). The former also called
    // itself, which would have been an infinite recursion had it ever applied.
    // MITE's getStrVsBlock already resolves efficiency per block+metadata and
    // its isRepairable() is the real implementation, so both are left alone.

    @Unique
    public Item setNoRepair() {
        canRepair = false;
        return (Item)(Object)this;
    }

    @Unique
    public boolean onBlockStartBreak(ItemStack itemstack, int X, int Y, int Z, EntityPlayer player) {
        return false;
    }

    @Unique
    public void onUsingItemTick(ItemStack stack, EntityPlayer player, int count) {
    }

    @Unique
    public boolean onLeftClickEntity(ItemStack stack, EntityPlayer player, Entity entity) {
        return false;
    }

    @Unique
    public Icon getIcon(ItemStack stack, int renderPass, EntityPlayer player, ItemStack usingItem, int useRemaining) {
        return null;
    }

    @Unique
    public int getRenderPasses(int metadata) {
        return this.requiresMultipleRenderPasses() ? 2 : 1;
    }

    @Unique
    public ItemStack getContainerItemStack(ItemStack itemStack) {
        if (!this.hasContainerItem()) {
            return null;
        }
        return new ItemStack(this.getContainerItem());
    }

    @Unique
    public int getEntityLifespan(ItemStack itemStack, World world) {
        return 6000;
    }

    @Unique
    public boolean hasCustomEntity(ItemStack stack) {
        return false;
    }

    @Unique
    public Entity createEntity(World world, Entity location, ItemStack itemstack) {
        return null;
    }

    @Unique
    public boolean onEntityItemUpdate(EntityItem entityItem) {
        return false;
    }

    @Unique
    public CreativeTabs[] getCreativeTabs() {
        return new CreativeTabs[]{ this.getCreativeTab() };
    }

    @Unique
    public float getSmeltingExperience(ItemStack item) {
        return -1;
    }

    @Unique
    public Icon getIcon(ItemStack stack, int pass) {
        // MITE does not have getIconFromDamageForRenderPass(int, int)
        return null;
    }

    @Unique
    public WeightedRandomChestContent getChestGenBase(ChestGenHooks chest, Random rnd, WeightedRandomChestContent original) {
        return original;
    }

    @Unique
    public boolean shouldPassSneakingClickToBlock(World par2World, int par4, int par5, int par6) {
        return false;
    }

    @Unique
    public void onArmorTickUpdate(World world, EntityPlayer player, ItemStack itemStack) {
    }

    @Unique
    public boolean isValidArmor(ItemStack stack, int armorType, Entity entity) {
        if (((Item)(Object)this) instanceof ItemArmor) {
            return ((ItemArmor)(Object)this).armorType == armorType;
        }
        if (armorType == 0) {
            return this.itemID == Block.pumpkin.blockID || this.itemID == Item.skull.itemID;
        }
        return false;
    }

    @Unique
    public boolean isPotionIngredient(ItemStack stack) {
        return this.isPotionIngredient();
    }

    @Unique
    public String getPotionEffect(ItemStack stack) {
        return this.getPotionEffect();
    }

    @Unique
    public boolean isBookEnchantable(ItemStack itemstack1, ItemStack itemstack2) {
        return true;
    }

    @Unique
    public float getDamageVsEntity(Entity par1Entity, ItemStack itemStack) {
        return 0.0F;
    }

    @Unique
    public String getArmorTexture(ItemStack stack, Entity entity, int slot, int layer) {
        return null;
    }

    @Unique
    public String getArmorTexture(ItemStack stack, Entity entity, int slot, String type) {
        return null;
    }

    @Unique
    public FontRenderer getFontRenderer(ItemStack stack) {
        return null;
    }

    @Unique
    public ModelBiped getArmorModel(EntityLivingBase entityLiving, ItemStack itemStack, int armorSlot) {
        return null;
    }

    @Unique
    public boolean onEntitySwing(EntityLivingBase entityLiving, ItemStack stack) {
        return false;
    }

    @Unique
    public void renderHelmetOverlay(ItemStack stack, EntityPlayer player, ScaledResolution resolution, float partialTicks, boolean hasScreen, int mouseX, int mouseY) {
    }

    @Unique
    public int getDamage(ItemStack stack) {
        return stack.getItemDamage();
    }

    @Unique
    public int getDisplayDamage(ItemStack stack) {
        return stack.getItemDamage();
    }

    // getMaxDamage(ItemStack) is NOT added here: MITE already declares it
    // (verified via javap), so Mixin discards the @Unique copy. The old body
    // returned a hardcoded 0, which would have reported every item as
    // indestructible had it ever replaced MITE's implementation.

    @Unique
    public boolean isDamaged(ItemStack stack) {
        return stack.getItemDamage() > 0;
    }

    @Unique
    public void setDamage(ItemStack stack, int damage) {
        stack.setItemDamage(damage);
    }

    @Unique
    public boolean canHarvestBlock(Block par1Block, ItemStack itemStack) {
        return false;
    }

    @Unique
    public boolean hasEffect(ItemStack par1ItemStack, int pass) {
        return this.hasEffect(par1ItemStack);
    }

    @Unique
    public int getItemStackLimit(ItemStack stack) {
        // MITE's getItemStackLimit may have different parameters
        return 64;
    }
}
