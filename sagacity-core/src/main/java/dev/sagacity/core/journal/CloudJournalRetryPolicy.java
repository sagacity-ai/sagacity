package dev.sagacity.core.journal;

import java.time.Duration;

/**
 * Retry policy for {@link CloudSideEffectJournal} HTTP calls.
 *
 * <h2>What is retryable</h2>
 * <ul>
 *   <li>Network errors ({@link java.io.IOException}) — the server may not have
 *       received the request at all, or the response was lost in transit.
 *   <li>HTTP 5xx — server-side error, may be transient (overload, restart).
 *   <li>HTTP 429 — rate limited; respect the back-off and retry.
 * </ul>
 *
 * <h2>What is NOT retryable</h2>
 * <ul>
 *   <li>HTTP 4xx (except 429) — bad request or auth failure. Retrying will not
 *       fix a malformed payload or an invalid API key.
 *   <li>{@link InterruptedException} — the calling thread has been interrupted;
 *       honour that signal immediately.
 * </ul>
 *
 * <h2>Idempotency</h2>
 * <p>Retrying {@code append} is safe because the server enforces a
 * {@code UNIQUE(team_id, saga_id, seq)} constraint. A duplicate insert returns
 * the existing entry rather than creating a second row — so a retry that arrives
 * after the first request succeeded (but before its response was received)
 * produces the correct result without a double-write.
 *
 * <h2>Backoff</h2>
 * <p>Exponential: {@code initialDelay * 2^(attempt-1)}, capped at
 * {@link #MAX_DELAY}.
 */
final class CloudJournalRetryPolicy {

	static final int DEFAULT_MAX_ATTEMPTS = 4;   // 1 try + 3 retries
	static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(200);
	static final Duration MAX_DELAY = Duration.ofSeconds(10);

	static final CloudJournalRetryPolicy DEFAULT =
			new CloudJournalRetryPolicy(DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY);

	private final int maxAttempts;
	private final Duration initialDelay;

	CloudJournalRetryPolicy(int maxAttempts, Duration initialDelay) {
		if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
		this.maxAttempts = maxAttempts;
		this.initialDelay = initialDelay;
	}

	int maxAttempts() {
		return maxAttempts;
	}

	/** True when a network-level exception should trigger a retry. */
	boolean isRetryableException(Exception ex) {
		// InterruptedException is a signal to stop — never retry
		return !(ex instanceof InterruptedException);
	}

	/** True when an HTTP status code should trigger a retry. */
	boolean isRetryableStatus(int httpStatus) {
		return httpStatus == 429 || (httpStatus >= 500 && httpStatus < 600);
	}

	/**
	 * How long to sleep before attempt {@code attemptNumber} (1-based).
	 * Attempt 1 always has zero delay.
	 */
	Duration delayBefore(int attemptNumber) {
		if (attemptNumber <= 1) return Duration.ZERO;
		// Retry index is 0-based: attempt 2 → index 0, attempt 3 → index 1, etc.
		long delayMs = initialDelay.toMillis() * (1L << (attemptNumber - 2)); // 2^(n-1)
		return Duration.ofMillis(Math.min(delayMs, MAX_DELAY.toMillis()));
	}
}
