package dev.sagacity.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import dev.sagacity.core.Reversibility;

/**
 * Marks a tool method as having a declared compensating action, executed if the
 * saga fails after this tool has run.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Compensable {

	/**
	 * Name of the compensation method in the same class. Required unless
	 * {@link #reversibility()} is {@link Reversibility#IRREVERSIBLE}.
	 * The method may take no arguments or a single
	 * {@link dev.sagacity.core.compensation.CompensationContext} argument.
	 */
	String by() default "";

	Reversibility reversibility() default Reversibility.COMPENSATABLE;

}
