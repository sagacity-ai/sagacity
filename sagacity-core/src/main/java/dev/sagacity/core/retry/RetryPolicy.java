package dev.sagacity.core.retry;

/**
 * Immutable retry policy for a single tool execution.
 *
 * <p>This is a pure value object with no framework dependencies — it lives in
 * {@code sagacity-core} and is assembled from annotation values by the Spring AI
 * layer ({@code sagacity-spring-ai}).
 *
 * <h2>Retry semantics</h2>
 * <ul>
 *   <li>{@code maxAttempts = 1} means one attempt total — no retries (the default).
 *   <li>{@code maxAttempts = 4} means one initial attempt plus up to three retries.
 *   <li>If {@code retryOn} is empty, no retry is performed regardless of
 *       {@code maxAttempts}. Explicitly listing exception types is required.
 *   <li>Backoff between retries: {@code initialDelayMs * multiplier^(attempt-1)}.
 *       Capped at {@link #MAX_DELAY_MS} to prevent unbounded waits.
 * </ul>
 */
public final class RetryPolicy {

	/** Maximum inter-attempt delay, regardless of backoff calculation. */
	static final long MAX_DELAY_MS = 30_000L;

	/** No retries — fail immediately on any exception. */
	public static final RetryPolicy NONE = new RetryPolicy(1, new Class[0], 100L, 2.0);

	private final int maxAttempts;

	@SuppressWarnings("rawtypes")
	private final Class[] retryOn;

	private final long initialDelayMs;

	private final double backoffMultiplier;

	@SuppressWarnings("rawtypes")
	public RetryPolicy(int maxAttempts, Class[] retryOn, long initialDelayMs, double backoffMultiplier) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + maxAttempts);
		}
		if (initialDelayMs < 0) {
			throw new IllegalArgumentException("initialDelayMs must be >= 0, got: " + initialDelayMs);
		}
		if (backoffMultiplier < 1.0) {
			throw new IllegalArgumentException("backoffMultiplier must be >= 1.0, got: " + backoffMultiplier);
		}
		this.maxAttempts = maxAttempts;
		this.retryOn = retryOn.clone();
		this.initialDelayMs = initialDelayMs;
		this.backoffMultiplier = backoffMultiplier;
	}

	/** Returns true if there is at least one retry type declared and maxAttempts > 1. */
	public boolean hasRetries() {
		return maxAttempts > 1 && retryOn.length > 0;
	}

	/**
	 * Returns true if the given throwable (or any of its causes) matches one of the
	 * declared retry-on types. Uses {@code isAssignableFrom} so subclasses match.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public boolean isRetryable(Throwable ex) {
		if (retryOn.length == 0) return false;
		Throwable current = ex;
		while (current != null) {
			for (Class retryType : retryOn) {
				if (retryType.isAssignableFrom(current.getClass())) {
					return true;
				}
			}
			current = current.getCause() != current ? current.getCause() : null;
		}
		return false;
	}

	public int maxAttempts() {
		return maxAttempts;
	}

	/**
	 * Delay in ms before attempt number {@code attemptNumber} (1-based).
	 * Attempt 1 always has 0 delay (it is the first try, not a retry).
	 */
	public long delayBeforeAttempt(int attemptNumber) {
		if (attemptNumber <= 1) return 0L;
		// Retry n is attempt n+1: delay = initialDelayMs * multiplier^(n-1)
		int retryIndex = attemptNumber - 2; // 0-based retry index
		double delay = initialDelayMs * Math.pow(backoffMultiplier, retryIndex);
		return Math.min((long) delay, MAX_DELAY_MS);
	}

}
