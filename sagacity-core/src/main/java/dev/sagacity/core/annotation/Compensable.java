package dev.sagacity.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import dev.sagacity.core.Reversibility;

/**
 * Marks a tool method as having a declared compensating action, executed if the
 * saga fails after this tool has run.
 *
 * <h2>Retry support</h2>
 * <p>Set {@link #retries} to attempt the tool more than once before giving up and
 * compensating. Retries are transparent to the journal: the audit trail shows only
 * the final EXECUTED or FAILED outcome, not individual attempts.
 *
 * <p>{@link #retryOn} narrows retries to specific exception types (whitelist).
 * When left empty (the default), <em>no retry is performed</em> regardless of
 * {@link #retries} — you must explicitly declare which exceptions are transient.
 * This is the safe default: an unknown exception may indicate a real business
 * failure or a partially-applied side effect, both of which should compensate
 * immediately rather than retry.
 *
 * <pre>{@code
 * @Tool(description = "Reserve inventory")
 * @Compensable(
 *     by = "releaseInventory",
 *     retries = 3,
 *     retryOn = { InventoryUnavailableException.class, SocketTimeoutException.class }
 * )
 * public String reserveInventory(String sku, int qty) { ... }
 * }</pre>
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

	/**
	 * Maximum number of retry attempts after the first failure.
	 * {@code 0} (default) means no retries — fail immediately and compensate.
	 * Only meaningful when {@link #retryOn} is also declared.
	 */
	int retries() default 0;

	/**
	 * Exception types that should trigger a retry.
	 * When empty (the default), no retry is attempted regardless of {@link #retries}.
	 * Subclasses of the listed types also match.
	 */
	Class<? extends Throwable>[] retryOn() default {};

}
