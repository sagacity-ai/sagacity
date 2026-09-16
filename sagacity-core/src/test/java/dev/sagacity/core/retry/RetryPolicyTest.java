package dev.sagacity.core.retry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

	// ── NONE constant ─────────────────────────────────────────────────────────

	@Test
	void none_hasNoRetries() {
		assertThat(RetryPolicy.NONE.hasRetries()).isFalse();
	}

	@Test
	void none_isNotRetryableForAnyException() {
		assertThat(RetryPolicy.NONE.isRetryable(new RuntimeException())).isFalse();
	}

	// ── hasRetries ────────────────────────────────────────────────────────────

	@Test
	void hasRetries_trueWhenMaxAttemptsGt1AndRetryOnDeclared() {
		RetryPolicy policy = new RetryPolicy(3, new Class[]{RuntimeException.class}, 100L, 2.0);
		assertThat(policy.hasRetries()).isTrue();
	}

	@Test
	void hasRetries_falseWhenRetryOnIsEmpty() {
		RetryPolicy policy = new RetryPolicy(3, new Class[0], 100L, 2.0);
		assertThat(policy.hasRetries()).isFalse();
	}

	@Test
	void hasRetries_falseWhenMaxAttemptsIsOne() {
		RetryPolicy policy = new RetryPolicy(1, new Class[]{RuntimeException.class}, 100L, 2.0);
		assertThat(policy.hasRetries()).isFalse();
	}

	// ── isRetryable ───────────────────────────────────────────────────────────

	@Test
	void isRetryable_trueWhenExceptionMatchesDeclaredType() {
		RetryPolicy policy = new RetryPolicy(3, new Class[]{IllegalStateException.class}, 100L, 2.0);
		assertThat(policy.isRetryable(new IllegalStateException("transient"))).isTrue();
	}

	@Test
	void isRetryable_trueForSubclassOfDeclaredType() {
		// IllegalArgumentException is a subclass of RuntimeException
		RetryPolicy policy = new RetryPolicy(3, new Class[]{RuntimeException.class}, 100L, 2.0);
		assertThat(policy.isRetryable(new IllegalArgumentException("sub"))).isTrue();
	}

	@Test
	void isRetryable_falseWhenExceptionDoesNotMatch() {
		RetryPolicy policy = new RetryPolicy(3, new Class[]{IllegalStateException.class}, 100L, 2.0);
		assertThat(policy.isRetryable(new NullPointerException())).isFalse();
	}

	@Test
	void isRetryable_trueWhenRootCauseMatches() {
		RetryPolicy policy = new RetryPolicy(3, new Class[]{IllegalStateException.class}, 100L, 2.0);
		RuntimeException wrapped = new RuntimeException("wrapper", new IllegalStateException("root"));
		assertThat(policy.isRetryable(wrapped)).isTrue();
	}

	@Test
	void isRetryable_falseWhenRetryOnIsEmpty() {
		RetryPolicy policy = new RetryPolicy(3, new Class[0], 100L, 2.0);
		assertThat(policy.isRetryable(new RuntimeException())).isFalse();
	}

	// ── delayBeforeAttempt ────────────────────────────────────────────────────

	@Test
	void delayBeforeAttempt_zeroForFirstAttempt() {
		RetryPolicy policy = new RetryPolicy(4, new Class[]{RuntimeException.class}, 100L, 2.0);
		assertThat(policy.delayBeforeAttempt(1)).isEqualTo(0L);
	}

	@Test
	void delayBeforeAttempt_initialDelayForSecondAttempt() {
		RetryPolicy policy = new RetryPolicy(4, new Class[]{RuntimeException.class}, 100L, 2.0);
		assertThat(policy.delayBeforeAttempt(2)).isEqualTo(100L);
	}

	@Test
	void delayBeforeAttempt_exponentialBackoff() {
		RetryPolicy policy = new RetryPolicy(5, new Class[]{RuntimeException.class}, 100L, 2.0);
		assertThat(policy.delayBeforeAttempt(2)).isEqualTo(100L);  // 100 * 2^0
		assertThat(policy.delayBeforeAttempt(3)).isEqualTo(200L);  // 100 * 2^1
		assertThat(policy.delayBeforeAttempt(4)).isEqualTo(400L);  // 100 * 2^2
		assertThat(policy.delayBeforeAttempt(5)).isEqualTo(800L);  // 100 * 2^3
	}

	@Test
	void delayBeforeAttempt_cappedAtMaxDelay() {
		RetryPolicy policy = new RetryPolicy(100, new Class[]{RuntimeException.class}, 1000L, 10.0);
		// Would be 1000 * 10^50 without the cap
		assertThat(policy.delayBeforeAttempt(52)).isEqualTo(RetryPolicy.MAX_DELAY_MS);
	}

	// ── constructor validation ────────────────────────────────────────────────

	@Test
	void constructor_throwsOnMaxAttemptsLessThanOne() {
		assertThatThrownBy(() -> new RetryPolicy(0, new Class[0], 100L, 2.0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maxAttempts");
	}

	@Test
	void constructor_throwsOnNegativeInitialDelay() {
		assertThatThrownBy(() -> new RetryPolicy(3, new Class[0], -1L, 2.0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("initialDelayMs");
	}

	@Test
	void constructor_throwsOnMultiplierLessThanOne() {
		assertThatThrownBy(() -> new RetryPolicy(3, new Class[0], 100L, 0.5))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("backoffMultiplier");
	}
}
