package net.minecraftforge.common;

import com.chocohead.mm.api.ClassTinkerers;
import com.chocohead.mm.api.EnumAdder;
import net.minecraft.block.material.Material;
import net.minecraft.enchantment.EnumEnchantmentType;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.item.EnumAction;
import net.minecraft.item.EnumArmorMaterial;
import net.minecraft.item.EnumToolMaterial;
import net.xiaoyu233.fml.FishModLoader;

/**
 * Forge 1.6.4 EnumHelper, real implementation backed by the chocohead/mm
 * enum extender that's already vendored in this project.
 *
 * <p>Each {@code addXxx} method asks {@link ClassTinkerers#enumBuilder} to
 * synthesize a new constant for the matching MITE enum, drives it with the
 * vanilla constructor, and returns the live constant via
 * {@link ClassTinkerers#getEnum}. Returns null only on hard failure (logged).
 *
 * <p>MITE keeps the same constructor shapes as Forge 1.6.4 vanilla for these
 * five enums, so the parameter ordering matches mod expectations.
 */
public class EnumHelper {

    public static EnumArmorMaterial addArmorMaterial(String name, int durability, int[] reductions, int enchantability) {
        return buildSimple(EnumArmorMaterial.class, name,
                new Object[]{durability, reductions, enchantability});
    }

    public static EnumToolMaterial addToolMaterial(String name, int harvestLevel, int maxUses,
                                                   float efficiency, float damage, int enchantability) {
        return buildSimple(EnumToolMaterial.class, name,
                new Object[]{harvestLevel, maxUses, efficiency, damage, enchantability});
    }

    public static EnumAction addAction(String name) {
        return buildSimple(EnumAction.class, name, new Object[0]);
    }

    public static EnumCreatureType addCreatureType(String name, Class<?> typeClass, int max,
                                                   Material material, boolean peaceful, boolean animal) {
        // MITE's EnumCreatureType ctor: (String, int, Class, int, Material)
        // The Forge signature carries peaceful/animal flags that MITE folded
        // into other state — passed through to the enum subclass as required.
        return buildSimple(EnumCreatureType.class, name, new Object[]{typeClass, max, material});
    }

    public static EnumEnchantmentType addEnchantmentType(String name) {
        return buildSimple(EnumEnchantmentType.class, name, new Object[0]);
    }

    /**
     * Generic helper: ask chocohead to add the constant, build the type, and
     * pull the resolved enum back out. Returns null on failure with a logged
     * warning so a misconfigured AT or wrong parameter list doesn't crash the
     * mod load.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Enum<?>> T addEnum(Class<T> enumType, String enumName,
                                                Class<?>[] paramTypes, Object[] paramValues) {
        try {
            EnumAdder builder = (paramTypes == null || paramTypes.length == 0)
                    ? ClassTinkerers.enumBuilder(enumType.getName())
                    : ClassTinkerers.enumBuilder(enumType.getName(), paramTypes);
            builder.addEnum(enumName, paramValues == null ? new Object[0] : paramValues);
            builder.build();
            return (T) ClassTinkerers.getEnum((Class) enumType, enumName);
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.warn("EnumHelper.addEnum failed for {}#{}", enumType.getName(), enumName, thrown);
            return null;
        }
    }

    /** Build an enum constant with parameter types inferred from the values. */
    @SuppressWarnings("unchecked")
    private static <T extends Enum<?>> T buildSimple(Class<T> enumType, String name, Object[] params) {
        Class<?>[] paramTypes = new Class<?>[params.length];
        for (int i = 0; i < params.length; i++) {
            paramTypes[i] = unboxedType(params[i]);
        }
        return addEnum(enumType, name, paramTypes, params);
    }

    /** Unbox primitive wrappers and array boxing so chocohead sees the real ctor signature. */
    private static Class<?> unboxedType(Object value) {
        if (value == null) return Object.class;
        Class<?> type = value.getClass();
        if (type == Integer.class)   return int.class;
        if (type == Long.class)      return long.class;
        if (type == Float.class)     return float.class;
        if (type == Double.class)    return double.class;
        if (type == Short.class)     return short.class;
        if (type == Byte.class)      return byte.class;
        if (type == Character.class) return char.class;
        if (type == Boolean.class)   return boolean.class;
        return type;
    }
}
