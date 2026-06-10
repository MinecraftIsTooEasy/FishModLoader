package net.minecraftforge.oredict;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingResult;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class ShapelessOreRecipe implements IRecipe
{
    private ItemStack output = null;
    private ArrayList input = new ArrayList();
    // -------- MITE IRecipe additions (stage 2 stubs) --------
    private float difficulty = 0;
    private boolean includeInLowest = false;
    private int[] skillsets = new int[0];
    private Material toolBenchHardnessMaterial = null;

    public ShapelessOreRecipe(Block result, Object... recipe){ this(new ItemStack(result), recipe); }

    public ShapelessOreRecipe(Item  result, Object... recipe){ this(new ItemStack(result), recipe); }

    public ShapelessOreRecipe(ItemStack result, Object... recipe)
    {
        output = result.copy();
        for (Object in : recipe)
        {
            if (in instanceof ItemStack)
            {
                input.add(((ItemStack)in).copy());
            }
            else if (in instanceof Item)
            {
                input.add(new ItemStack((Item)in));
            }
            else if (in instanceof Block)
            {
                input.add(new ItemStack((Block)in));
            }
            else if (in instanceof String)
            {
                input.add(OreDictionary.getOres((String)in));
            }
            else
            {
                String ret = "Invalid shapeless ore recipe: ";
                for (Object tmp :  recipe)
                {
                    ret += tmp + ", ";
                }
                ret += output;
                throw new RuntimeException(ret);
            }
        }
    }

    ShapelessOreRecipe(ShapelessRecipes recipe, Map<ItemStack, String> replacements)
    {
        output = recipe.getRecipeOutput();

        for(ItemStack ingred : ((List<ItemStack>)recipe.recipeItems))
        {
            Object finalObj = ingred;
            for(Entry<ItemStack, String> replace : replacements.entrySet())
            {
                if(OreDictionary.itemMatches(replace.getKey(), ingred, false))
                {
                    finalObj = OreDictionary.getOres(replace.getValue());
                    break;
                }
            }
            input.add(finalObj);
        }
    }

    @Override
    public int getRecipeSize(){ return input.size(); }

    @Override
    public ItemStack getRecipeOutput(){ return output; }

    @Override
    public CraftingResult getCraftingResult(InventoryCrafting var1){
        return new CraftingResult(output.copy(), difficulty, skillsets, this);
    }

    @Override public ItemStack[] getComponents() { return new ItemStack[0]; }
    @Override public IRecipe setDifficulty(float d) { this.difficulty = d; return this; }
    @Override public IRecipe scaleDifficulty(float s) { this.difficulty *= s; return this; }
    @Override public float getUnmodifiedDifficulty() { return difficulty; }
    @Override public void setIncludeInLowestCraftingDifficultyDetermination() { this.includeInLowest = true; }
    @Override public boolean getIncludeInLowestCraftingDifficultyDetermination() { return includeInLowest; }

    @Override public void setSkillset(int s) { this.skillsets = new int[]{s}; }

    @Override public int[] getSkillsets() { return skillsets; }

    @Override public void setSkillsets(int[] s) { this.skillsets = s; }

    @Override public Material getMaterialToCheckToolBenchHardnessAgainst() { return toolBenchHardnessMaterial; }

    @Override public void setMaterialToCheckToolBenchHardnessAgainst(Material m) { this.toolBenchHardnessMaterial = m; }

    @Override
    public boolean matches(InventoryCrafting var1, World world)
    {
        ArrayList required = new ArrayList(input);

        for (int x = 0; x < var1.getSizeInventory(); x++)
        {
            ItemStack slot = var1.getStackInSlot(x);

            if (slot != null)
            {
                boolean inRecipe = false;
                Iterator req = required.iterator();

                while (req.hasNext())
                {
                    boolean match = false;

                    Object next = req.next();

                    if (next instanceof ItemStack)
                    {
                        match = checkItemEquals((ItemStack)next, slot);
                    }
                    else if (next instanceof ArrayList)
                    {
                        for (ItemStack item : (ArrayList<ItemStack>)next)
                        {
                            match = match || checkItemEquals(item, slot);
                        }
                    }

                    if (match)
                    {
                        inRecipe = true;
                        required.remove(next);
                        break;
                    }
                }

                if (!inRecipe)
                {
                    return false;
                }
            }
        }

        return required.isEmpty();
    }

    private boolean checkItemEquals(ItemStack target, ItemStack input)
    {
        return (target.itemID == input.itemID && (target.getItemDamage() == OreDictionary.WILDCARD_VALUE || target.getItemDamage() == input.getItemDamage()));
    }

    /**
     * Returns the input for this recipe, any mod accessing this value should never
     * manipulate the values in this array as it will effect the recipe itself.
     * @return The recipes input vales.
     */
    public ArrayList getInput()
    {
        return this.input;
    }
}
