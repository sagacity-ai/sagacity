package dev.sagacity.springai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.saga.SagaStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests retry behaviour in {@link SagacityToolCallback}.
 *
 * Uses a real {@link Sagacity} instance with in-memory store — no mocks.
 */
class RetryTest {

    static class TransientException extends RuntimeException {
        TransientException(String msg) { super(msg); }
    }

    static class NonTransientException extends RuntimeException {
        NonTransientException(String msg) { super(msg); }
    }

    static class RetryableTools {
        final AtomicInteger callCount = new AtomicInteger(0);
        final AtomicInteger compensationCount = new AtomicInteger(0);
        final int failUntilAttempt;

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

        @Tool(description = "Tool with no retry config")
        @Compensable(by = "undoTool")
        public String noRetryTool(String input) {
            callCount.incrementAndGet();
            throw new TransientException("would be retryable if configured");
        }

        @Compensation
        public void undoTool(CompensationContext ctx) {}
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("retries on transient failure and succeeds before exhausting")
    void retriesOnTransientFailureAndSucceeds() {
        RetryableTools tools = new RetryableTools(3); // fails 1+2, succeeds on 3
        Sagacity sagacity = Sagacity.create();
        var callbacks = sagacity.wrap(tools);

        SagaResult<Void> result = sagacity.saga("retry-saga-1", () -> {
            for (var cb : callbacks) {
                if (cb.getToolDefinition().name().equals("flakyTool")) {
                    cb.call("{\"input\":\"test\"}");
                }
            }
        });

        assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(tools.callCount.get()).isEqualTo(3);

        // Journal shows Intent → Executed only — retry attempts are transparent
        var entries = sagacity.auditStore().findBySagaId("retry-saga-1");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).phase()).isInstanceOf(Phase.Intent.class);
        assertThat(entries.get(1).phase()).isInstanceOf(Phase.Executed.class);
        // Result is in the Executed phase data
        assertThat(((Phase.Executed) entries.get(1).phase()).result())
                .isEqualTo("\"success-on-attempt-3\"");
    }

    @Test
    @DisplayName("compensates when all retries exhausted")
    void compensatesWhenAllRetriesExhausted() {
        RetryableTools tools = new RetryableTools(999); // always fails
        Sagacity sagacity = Sagacity.create();
        var callbacks = sagacity.wrap(tools);

        SagaResult<Void> result = sagacity.saga("retry-saga-2", () -> {
            for (var cb : callbacks) {
                if (cb.getToolDefinition().name().equals("flakyTool")) {
                    cb.call("{\"input\":\"test\"}");
                }
            }
        });

        // 1 initial + 3 retries = 4 total
        assertThat(tools.callCount.get()).isEqualTo(4);
        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);

        // Journal shows Intent → Failed
        var entries = sagacity.auditStore().findBySagaId("retry-saga-2");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).phase()).isInstanceOf(Phase.Intent.class);
        assertThat(entries.get(1).phase()).isInstanceOf(Phase.Failed.class);
    }

    @Test
    @DisplayName("does not retry non-whitelisted exception")
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

        assertThat(tools.callCount.get()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("does not retry when no retry config declared")
    void doesNotRetryWhenNoRetryConfig() {
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

        assertThat(tools.callCount.get()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("succeeds on first attempt — no retry needed")
    void succeedsOnFirstAttempt() {
        RetryableTools tools = new RetryableTools(1); // succeeds immediately
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
    @DisplayName("retry is transparent to journal — no intermediate entries")
    void retryIsTransparentToJournal() {
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

        var entries = sagacity.auditStore().findBySagaId("retry-saga-6");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).phase().discriminator()).isEqualTo("Intent");
        assertThat(entries.get(1).phase().discriminator()).isEqualTo("Executed");
    }
}
