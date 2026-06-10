package cpw.mods.fml.common.network;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stub for cpw.mods.fml.common.network.NetworkMod (Forge 1.6.4).
 * Marks a mod's networking declaration. Currently informational only.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NetworkMod {
    Class<?> serverPacketHandlerSpec() default Object.class;
    Class<?> clientPacketHandlerSpec() default Object.class;
    String[] channels() default {};
    String versionBounds() default "";
    boolean clientSideRequired() default false;
    boolean serverSideRequired() default false;
    String packetHandler() default "";
    String tinyPacketHandler() default "";
    Class<?> connectionHandler() default Object.class;
}
