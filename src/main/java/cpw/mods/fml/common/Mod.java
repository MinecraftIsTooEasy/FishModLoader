package cpw.mods.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stub for cpw.mods.fml.common.Mod (Forge 1.6.4 FML).
 *
 * Marks a class as a Forge mod entry point. The mod loader scans for this
 * annotation and instantiates a {@code ModContainer} per match.
 *
 * NOTE: This is a compile-time / class-loading shim. The lifecycle dispatcher
 * that actually invokes @PreInit/@Init/@PostInit methods will be wired up in
 * a later stage (FishModLoader Forge compat: lifecycle phase).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {

    String modid();

    String name() default "";

    String version() default "";

    String dependencies() default "";

    boolean useMetadata() default false;

    String acceptedMinecraftVersions() default "";

    String bukkitPlugin() default "";

    String[] asmHookClass() default {};

    String acceptableRemoteVersions() default "";

    String acceptableSaveVersions() default "";

    String certificateFingerprint() default "";

    /** Marks pre-initialization callback (1.6.4 legacy name). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface PreInit {}

    /** Marks initialization callback. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Init {}

    /** Marks post-initialization callback. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface PostInit {}

    /** Marks server-starting callback. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface ServerStarting {}

    /** Marks server-about-to-start callback (fires before world load). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface ServerAboutToStart {}

    /** Marks server-started callback. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface ServerStarted {}

    /** Marks server-stopping callback. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface ServerStopping {}

    /** Marks server-stopped callback. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface ServerStopped {}

    /** Generic event handler (replaces the per-phase annotations in newer FML). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface EventHandler {}

    /** Marks a static field that should receive the mod instance after construction. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Instance {
        String value() default "";
    }

    /** Designates a class field that should hold a side-specific proxy. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface SidedProxy {
        String clientSide() default "";
        String serverSide() default "";
        String modId() default "";
    }

    /** Marks a static field as the metadata holder for the mod. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Metadata {
        String value() default "";
    }

    /** Block/Item registration helpers (used by some 1.6 mods). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Block {
        String name();
        String[] itemTypeNames() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Item {
        String name();
        String typeName();
    }
}
