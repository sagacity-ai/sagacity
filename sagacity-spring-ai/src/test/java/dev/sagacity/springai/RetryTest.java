package dev.sagacity.springai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.saga.SagaStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests retry behaviour in {@link SagacityToolCallback}.
 *
 * <p>Uses a real {@link Sagacity} instance with in-memory journal — no mocks.
 * Tools are implemented with AtomicInteger call counters to verify retry counts.
 */
class RetryTest {

	/** Marker exception — declared transient in the retry whitelist. */
	static class TransientException extends RuntimeException {
		TransientException(String msg) { super(msg); }
	}

	/** Another exception — not in the whitelist, should not trigger retry. */
	static class NonTransientException extends RuntimeException {
		NonTransientException(String msg) { super(msg); }
	}

	// ── Tool beans ─────────────────────────────────────────────────────────────

	static class RetryableTools {

		final AtomicInteger callCount = new AtomicInteger(0);
		final AtomicInteger compensationCount = new AtomicInteger(0);
		final int failUntilAttempt; // succeed on this attempt number (1-based)

		RetryableTools(int failUntilAttempt) {
			this.failUntilAttempt = failUntilAttempt;
		}

		@Tool(description = "Flaky tool that fails a configurable number of times")
		@Compensable(by = "undoFlakyTool", retries = 3, retryOn = { TransientException.class })
		public String flakyTool(String input) {
			int attempt = callCount.incrementAndGet();
			if (attempt < failUntilAttempt) {
				throw new TransientException("transient failure on attempt " + attempt);
			}
			return "success-on-attempt-" + attempt;
		}

		@Compensation
		public void undoFlakyTool(CompensationContext ctx) {
			compensationCount.incrementAndGet();
		}
	}

	static class NonRetryableTools {

		final AtomicInteger callCount = new AtomicInteger(0);

		@Tool(description = "Tool that throws a non-retryable exception")
		@Compensable(by = "undoTool", retries = 3, retryOn = { TransientException.class })
		public String nonRetryableTool(String input) {
			callCount.incrementAndGet();
			throw new NonTransientException("business failure — do not retry");
		}

		@Compensation
		public void undoTool(CompensationContext ctx) {}
	}

	static class NoRetryTools {

		final AtomicInteger callCount = new AtomicInteger(0);

		@Tool(description = "Tool with no retry config — should fail immediately")
		@Compensable(by = "undoTool") // no retries declared
		public String noRetryTool(String input) {
			callCount.incrementAndGet();
			throw new TransientException("would be retryable if configured");
		}

		@Compensation
		public void undoTool(CompensationContext ctx) {}
	}

	// ── Tests ─────────────────────────────────────────────────────────────────

	@Test
	void retriesOnTransientFailure_andSucceedsBeforeExhausting() {
		// Fails on attempts 1 and 2, succeeds on attempt 3
		RetryableTools tools = new RetryableTools(3);
		Sagacity sagacity = Sagacity.create();
		var callbacks = sagacity.wrap(tools);

		SagaResult<Void> result = sagacity.saga("retry-saga-1", () -> {
			for (var cb : callbacks) {
				if (cb.getToolDefinition().name().equals("flakyTool")) {
					cb.call("{\"input\":\"test\"}");
				}
			}
		});

		// Saga completes — success on 3rd attempt
		assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
		assertThat(tools.callCount.get()).isEqualTo(3);

		// Journal shows INTENT → EXECUTED only (retry attempts are transparent)
		var entries = sagacity.journal().entries("retry-saga-1");
		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).phase()).isEqualTo(Phase.INTENT);
		assertThat(entries.get(1).phase()).isEqualTo(Phase.EXECUTED);
		assertThat(entries.get(1).payload()).isEqualTo("\"success-on-attempt-3\"");
	}

	@Test
	void compensatesWhenAllRetriesExhausted() {
		// Always fails — never succeeds
		RetryableTools tools = new RetryableTools(999);
		Sagacity sagacity = Sagacity.create();
		var callbacks = sagacity.wrap(tools);

		SagaResult<Void> result = sagacity.saga("retry-saga-2", () -> {
			for (var cb : callbacks) {
				if (cb.getToolDefinition().name().equals("flakyTool")) {
					cb.call("{\"input\":\"test\"}");
				}
			}
		});

		// 1 initial attempt + 3 retries = 4 total calls
		assertThat(tools.callCount.get()).isEqualTo(4);
		assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);

		// Journal shows INTENT → FAILED (no intermediate retry entries)
		var entries = sagacity.journal().entries("retry-saga-2");
		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).phase()).isEqualTo(Phase.INTENT);
		assertThat(entries.get(1).phase()).isEqualTo(Phase.FAILED);
	}

	@Test
	void doesNotRetryNonWhitelistedException() {
		NonRetryableTools tools = new NonRetryableTools();
		Sagacity sagacity = Sagacity.create();
		var callbacks = sagacity.wrap(tools);

		SagaResult<Void> result = sagacity.saga("retry-saga-3", () -> {
			for (var cb : callbacks) {
				if (cb.getToolDefinition().name().equals("nonRetryableTool")) {
					cb.call("{\"input\":\"test\"}");
				}
			}
		});

		// NonTransientException is not in retryOn — should fail immediately, no retries
		assertThat(tools.callCount.get()).isEqualTo(1);
		assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
	}

	@Test
	void doesNotRetryWhenNoRetryConfigDeclared() {
		NoRetryTools tools = new NoRetryTools();
		Sagacity sagacity = Sagacity.create();
		var callbacks = sagacity.wrap(tools);

		SagaResult<Void> result = sagacity.saga("retry-saga-4", () -> {
			for (var cb : callbacks) {
				if (cb.getToolDefinition().name().equals("noRetryTool")) {
					cb.call("{\"input\":\"test\"}");
				}
			}
		});

		// @Compensable has no retryOn — TransientException should not be retried
		assertThat(tools.callCount.get()).isEqualTo(1);
		assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
	}

	@Test
	void succeedsOnFirstAttempt_noRetryNeeded() {
		// Succeeds immediately — should call exactly once
		RetryableTools tools = new RetryableTools(1);
		Sagacity sagacity = Sagacity.create();
		var callbacks = sagacity.wrap(tools);

		SagaResult<Void> result = sagacity.saga("retry-saga-5", () -> {
			for (var cb : callbacks) {
				if (cb.getToolDefinition().name().equals("flakyTool")) {
					cb.call("{\"input\":\"test\"}");
				}
			}
		});

		assertThat(tools.callCount.get()).isEqualTo(1);
		assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
	}

	@Test
	void retryIsTransparentToJournal_noIntermediateEntries() {
		// Fails twice then succeeds — journal must show exactly INTENT + EXECUTED
		RetryableTools tools = new RetryableTools(3);
		Sagacity sagacity = Sagacity.create();
		var callbacks = sagacity.wrap(tools);

		sagacity.saga("retry-saga-6", () -> {
			for (var cb : callbacks) {
				if (cb.getToolDefinition().name().equals("flakyTool")) {
					cb.call("{\"input\":\"x\"}");
				}
			}
		});

		var entries = sagacity.journal().entries("retry-saga-6");
		// Exactly 2 entries — no "RETRY" or intermediate phases
		assertThat(entries).hasSize(2);
		assertThat(entries.stream().map(e -> e.phase().name()))
				.containsExactly("INTENT", "EXECUTED");
	}
}
