package javax.annotation;

import javax.annotation.meta.TypeQualifier;
import javax.annotation.meta.When;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Documented
@TypeQualifier(
   applicableTo = CharSequence.class
)
@Retention(RetentionPolicy.RUNTIME)
public @interface Syntax {
   String value();

   When when() default When.ALWAYS;
}
