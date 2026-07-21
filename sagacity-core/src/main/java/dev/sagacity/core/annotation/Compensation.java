package dev.sagacity.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional marker for a method referenced by {@link Compensable#by()}. Purely
 * documentation in v0.1; the binding is by method name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Compensation {

}
