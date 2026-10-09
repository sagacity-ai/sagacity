package dev.sagacity.lc4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.sagacity.audit.AuditStore;
import dev.sagacity.audit.Phase;
import dev.sagacity.recovery.CompensationRunner;
import dev.sagacity.recovery.RetryPolicy;

import java.lang.reflect.Method;

/**
 * LangChain4j {@link ToolExecutor} that wraps a single {@code @Tool} method
 * with Sagacity governance: tamper-evident audit trail, compensation on failure,
 * and optional retry.
 *
 * <p>Uses {@code propagateToolExecutionExceptions=true} so tool exceptions are
 * thrown as {@link ToolExecutionException} and can be inspected by the retry
 * policy and compensation runner.
 *
 * <h2>Saga scope</h2>
 * <p>The {@code memoryId} passed by LangChain4j is used as the saga ID. If
 * {@code memoryId} is null, the tool executes as a pass-through — nothing is
 * journaled and no compensation runs.
 */
final class SagacityToolExecutor implements ToolExecutor {

    private final DefaultToolExecutor delegate;
    private final String toolName;
    private final AuditStore auditStore;
    private final CompensationRunner compensationRunner;
    private final RetryPolicy retryPolicy;

    SagacityToolExecutor(
            Object toolBean,
            Method method,
            String toolName,
            AuditStore auditStore,
            CompensationRunner compensationRunner,
            RetryPolicy retryPolicy) {
        this.delegate = DefaultToolExecutor.builder()
                .object(toolBean)
                .originalMethod(method)
                .methodToInvoke(method)
                .propagateToolExecutionExceptions(true)
                .build();
        this.toolName = toolName;
        this.auditStore = auditStore;
        this.compensationRunner = compensationRunner;
        this.retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.NONE;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        if (memoryId == null) {
            // Outside saga scope — pass-through, no journaling
            return delegate.execute(request, null);
        }

        String sagaId = memoryId.toString();
        String input = request.arguments() != null ? request.arguments() : "";
        InvocationContext context = InvocationContext.builder().chatMemoryId(memoryId).build();

        auditStore.append(sagaId, toolName, new Phase.Intent(), input);

        int maxAttempts = retryPolicy.hasRetries() ? retryPolicy.maxAttempts() : 1;
        ToolExecutionException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                long delayMs = retryPolicy.delayBeforeAttempt(attempt);
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            try {
                ToolExecutionResult result = delegate.executeWithContext(request, context);
                String resultText = result.resultText() != null ? result.resultText() : "";
                auditStore.append(sagaId, toolName, new Phase.Executed(resultText), input);
                return resultText;
            } catch (ToolExecutionException ex) {
                lastException = ex;
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

                // Check retry whitelist against the original cause
                RuntimeException causeAsRuntime = cause instanceof RuntimeException rc ? rc : ex;
                boolean retryable = retryPolicy.hasRetries()
                        && retryPolicy.isRetryable(causeAsRuntime);
                boolean attemptsRemaining = attempt < maxAttempts;

                if (retryable && attemptsRemaining) {
                    continue; // transparent retry — no journal entry
                }

                // Non-retryable or exhausted — journal Failed and compensate
                String detail = cause.getMessage() != null
                        ? cause.getMessage() : cause.getClass().getSimpleName();
                auditStore.append(sagaId, toolName, new Phase.Failed(detail), input);
                compensationRunner.compensate(sagaId);
                return detail;
            }
        }

        // Reached only on interrupt
        Throwable root = lastException != null
                ? (lastException.getCause() != null ? lastException.getCause() : lastException)
                : new IllegalStateException("interrupted");
        String detail = root.getMessage() != null ? root.getMessage() : "interrupted";
        auditStore.append(sagaId, toolName, new Phase.Failed(detail), input);
        compensationRunner.compensate(sagaId);
        return detail;
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        Object memoryId = context != null ? context.chatMemoryId() : null;
        String result = execute(request, memoryId);
        return ToolExecutionResult.builder().resultText(result).build();
    }
}
