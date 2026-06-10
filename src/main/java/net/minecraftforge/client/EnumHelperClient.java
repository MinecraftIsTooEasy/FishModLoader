package net.minecraftforge.client;

import net.minecraft.world.EnumGameType;
import net.minecraftforge.common.EnumHelper;

/**
 * Client-side companion to {@link EnumHelper}. Forge 1.6.4 split a few enum
 * helpers into a client-only class for things only the client side cares
 * about. We delegate to the real enum extender via {@link EnumHelper}.
 */
public class EnumHelperClient extends EnumHelper {

    /**
     * Generic enum addition convenience overload. Mirrors the upstream Forge
     * {@code EnumHelperClient.addEnum(Class, String, Object...)} which
     * inferred the constructor types from the supplied values.
     */
    @SafeVarargs
    public static <T extends Enum<?>> T addEnum(Class<T> enumType, String enumName, Object... paramValues) {
        Class<?>[] paramTypes = new Class<?>[paramValues.length];
        for (int i = 0; i < paramValues.length; i++) {
            paramTypes[i] = paramValues[i] == null ? Object.class : paramValues[i].getClass();
        }
        return EnumHelper.addEnum(enumType, enumName, paramTypes, paramValues);
    }

    /**
     * Add a new {@link EnumGameType}. MITE's constructor is
     * {@code (id:int, name:String)} so we forward those two values.
     */
    public static EnumGameType addGameType(String name, int id, String typeName) {
        return EnumHelper.addEnum(EnumGameType.class, name,
                new Class<?>[]{int.class, String.class},
                new Object[]{id, typeName});
    }
}
