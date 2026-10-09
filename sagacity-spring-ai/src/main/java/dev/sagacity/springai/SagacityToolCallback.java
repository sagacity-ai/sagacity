package dev.sagacity.springai;

import dev.sagacity.control.Reversibility;
import dev.sagacity.control.ApprovalRequest;
import dev.sagacity.control.ApprovalStore;
import dev.sagacity.audit.AuditStore;
import dev.sagacity.audit.HashChain;
import dev.sagacity.audit.Phase;
import dev.sagacity.recovery.RetryPolicy;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorates a Spring AI {@link ToolCallback} with side-effect journaling,
 * approval gate support, and optional retry logic.
 *
 * <h2>Retry behaviour</h2>
 * <p>When a {@link RetryPolicy} with retries is configured, transient failures
 * are retried transparently — the journal records only the final outcome
 * ({@link Phase.Executed} on success, {@link Phase.Failed} when retries are
 * exhausted). Individual retry attempts are not journaled, keeping the audit
 * trail clean.
 *
 * <h2>Decoration happens at the callback level</h2>
 * <p>Not at ToolCallingManager level — deliberately: DefaultToolCallingManager
 * catches ToolExecutionException and converts it to an error message for the
 * model, so a manager-level decorator never observes the raw failure. This
 * wrapper sits inside that catch and sees it first.
 *
 * <p>Outside a saga scope the wrapper is a pass-through: the tool stays usable
 * in non-saga flows and nothing is journaled.
 */
public final class SagacityToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    private final AuditStore auditStore;

    private final ApprovalStore approvalStore;

    private final Reversibility reversibility;

    private final RetryPolicy retryPolicy;

    SagacityToolCallback(ToolCallback delegate, AuditStore auditStore, ApprovalStore approvalStore,
            Reversibility reversibility, RetryPolicy retryPolicy) {
        this.delegate = delegate;
        this.auditStore = auditStore;
        this.approvalStore = approvalStore;
        this.reversibility = reversibility;
        this.retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.NONE;
    }

    /** Backward-compatible — no approval gate, no retries. */
    SagacityToolCallback(ToolCallback delegate, AuditStore auditStore) {
        this(delegate, auditStore, null, Reversibility.COMPENSATABLE, RetryPolicy.NONE);
    }

    /** Backward-compatible — approval gate, no retries. */
    SagacityToolCallback(ToolCallback delegate, AuditStore auditStore, ApprovalStore approvalStore,
            Reversibility reversibility) {
        this(delegate, auditStore, approvalStore, reversibility, RetryPolicy.NONE);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return this.delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return this.delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String sagaId = SagaScope.currentSagaId();
        if (sagaId == null) {
            return this.delegate.call(toolInput, toolContext);
        }

        String toolName = getToolDefinition().name();

        // Approval gate for IRREVERSIBLE tools
        if (this.reversibility == Reversibility.IRREVERSIBLE && this.approvalStore != null) {
            var intentEntry = this.auditStore.append(sagaId, toolName,
                    new Phase.AwaitingApproval(), toolInput);
            String inputHash = HashChain.sha256(toolInput);
            this.approvalStore.save(
                    new ApprovalRequest(sagaId, intentEntry.seq(), toolName, toolInput, inputHash));
            SagaScope.markAwaitingApproval(toolName, intentEntry.seq());
            return "[AWAITING_APPROVAL] Tool '" + toolName + "' requires human approval before execution.";
        }

        this.auditStore.append(sagaId, toolName, new Phase.Intent(), toolInput);

        int maxAttempts = retryPolicy.hasRetries() ? retryPolicy.maxAttempts() : 1;
        RuntimeException lastException = null;

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
                String result = this.delegate.call(toolInput, toolContext);
                this.auditStore.append(sagaId, toolName,
                        new Phase.Executed(result != null ? result : ""), toolInput);
                return result;
            } catch (RuntimeException ex) {
                lastException = ex;

                boolean retryable = retryPolicy.hasRetries() && retryPolicy.isRetryable(ex);
                boolean attemptsRemaining = attempt < maxAttempts;

                if (retryable && attemptsRemaining) {
                    continue;
                }

                Throwable rootCause = ex.getCause() != null ? ex.getCause() : ex;
                String detail = rootCause.getMessage() != null
                        ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
                this.auditStore.append(sagaId, toolName, new Phase.Failed(detail), toolInput);
                SagaScope.markFailed(rootCause);
                throw ex;
            }
        }

        Throwable rootCause = lastException != null
                ? (lastException.getCause() != null ? lastException.getCause() : lastException)
                : new IllegalStateException("Retry loop exited without result");
        String detail = rootCause.getMessage() != null
                ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
        this.auditStore.append(sagaId, toolName, new Phase.Failed(detail), toolInput);
        SagaScope.markFailed(rootCause);
        if (lastException != null) throw lastException;
        throw new IllegalStateException("Tool execution failed after retry interruption");
    }
}
