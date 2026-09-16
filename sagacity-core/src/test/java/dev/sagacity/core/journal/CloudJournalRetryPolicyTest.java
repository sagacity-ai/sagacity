package dev.sagacity.core.journal;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudJournalRetryPolicyTest {

	private final CloudJournalRetryPolicy policy =
			new CloudJournalRetryPolicy(4, Duration.ofMillis(100));

	// ── isRetryableStatus ─────────────────────────────────────────────────────

	@Test
	void isRetryableStatus_trueFor500() {
		assertThat(policy.isRetryableStatus(500)).isTrue();
	}

	@Test
	void isRetryableStatus_trueFor503() {
		assertThat(policy.isRetryableStatus(503)).isTrue();
	}

	@Test
	void isRetryableStatus_trueFor429() {
		assertThat(policy.isRetryableStatus(429)).isTrue();
	}

	@Test
	void isRetryableStatus_falseFor200() {
		assertThat(policy.isRetryableStatus(200)).isFalse();
	}

	@Test
	void isRetryableStatus_falseFor201() {
		assertThat(policy.isRetryableStatus(201)).isFalse();
	}

	@Test
	void isRetryableStatus_falseFor400() {
		assertThat(policy.isRetryableStatus(400)).isFalse();
	}

	@Test
	void isRetryableStatus_falseFor401() {
		assertThat(policy.isRetryableStatus(401)).isFalse();
	}

	@Test
	void isRetryableStatus_falseFor403() {
		assertThat(policy.isRetryableStatus(403)).isFalse();
	}

	@Test
	void isRetryableStatus_falseFor404() {
		assertThat(policy.isRetryableStatus(404)).isFalse();
	}

	// ── isRetryableException ──────────────────────────────────────────────────

	@Test
	void isRetryableException_trueForIOException() {
		assertThat(policy.isRetryableException(new IOException("network error"))).isTrue();
	}

	@Test
	void isRetryableException_falseForInterruptedException() {
		assertThat(policy.isRetryableException(new InterruptedException())).isFalse();
	}

	// ── delayBefore ───────────────────────────────────────────────────────────

	@Test
	void delayBefore_zeroForFirstAttempt() {
		assertThat(policy.delayBefore(1)).isEqualTo(Duration.ZERO);
	}

	@Test
	void delayBefore_initialDelayForSecondAttempt() {
		assertThat(policy.delayBefore(2)).isEqualTo(Duration.ofMillis(100));
	}

	@Test
	void delayBefore_exponentialForSubsequentAttempts() {
		// 100ms, 200ms, 400ms
		assertThat(policy.delayBefore(2).toMillis()).isEqualTo(100L);
		assertThat(policy.delayBefore(3).toMillis()).isEqualTo(200L);
		assertThat(policy.delayBefore(4).toMillis()).isEqualTo(400L);
	}

	@Test
	void delayBefore_cappedAtMaxDelay() {
		CloudJournalRetryPolicy aggressivePolicy =
				new CloudJournalRetryPolicy(20, Duration.ofSeconds(1));
		// 1000 * 2^15 would be 32768000ms without cap
		assertThat(aggressivePolicy.delayBefore(17).toMillis())
				.isEqualTo(CloudJournalRetryPolicy.MAX_DELAY.toMillis());
	}

	// ── DEFAULT constant ──────────────────────────────────────────────────────

	@Test
	void default_hasExpectedMaxAttempts() {
		assertThat(CloudJournalRetryPolicy.DEFAULT.maxAttempts())
				.isEqualTo(CloudJournalRetryPolicy.DEFAULT_MAX_ATTEMPTS);
	}

	@Test
	void default_hasExpectedInitialDelay() {
		assertThat(CloudJournalRetryPolicy.DEFAULT.delayBefore(2))
				.isEqualTo(CloudJournalRetryPolicy.DEFAULT_INITIAL_DELAY);
	}

	// ── constructor validation ────────────────────────────────────────────────

	@Test
	void constructor_throwsOnZeroMaxAttempts() {
		assertThatThrownBy(() -> new CloudJournalRetryPolicy(0, Duration.ofMillis(100)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maxAttempts");
	}
}
