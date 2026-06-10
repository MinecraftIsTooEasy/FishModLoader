package cpw.mods.fml.relauncher;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stub for cpw.mods.fml.relauncher.SideOnly.
 * Marks a method/field/class as only present on one side.
 * Bytecode stripping isn't implemented yet — the annotation is currently
 * informational only.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.TYPE, ElementType.CONSTRUCTOR})
public @interface SideOnly {
    Side value();
}
