package dev.sagacity.springai;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.approval.ApprovalRequest;
import dev.sagacity.core.approval.ApprovalStore;
import dev.sagacity.core.journal.HashChain;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.journal.SideEffectJournal;
import dev.sagacity.core.retry.RetryPolicy;
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
 * (EXECUTED on success, FAILED when retries are exhausted). Individual retry
 * attempts are not journaled, keeping the audit trail clean.
 *
 * <p>Retry fires only when:
 * <ol>
 *   <li>The exception matches one of the declared {@code retryOn} types, AND
 *   <li>Remaining attempts > 0.
 * </ol>
 * All other exceptions fail fast and trigger compensation immediately.
 *
 * <h2>Decoration happens at the callback level</h2>
 * <p>Not at ToolCallingManager level — deliberately: DefaultToolCallingManager
 * catches ToolExecutionException and converts it to an error message for the
 * model, so a manager-level decorator never observes the raw failure. This
 * wrapper sits inside that catch and sees it first: it journals FAILED, marks
 * the saga scope failed, then rethrows so Spring AI's normal error handling
 * still applies.
 *
 * <p>Outside a saga scope the wrapper is a pass-through: the tool stays usable
 * in non-saga flows and nothing is journaled.
 */
public final class SagacityToolCallback implements ToolCallback {

	private final ToolCallback delegate;

	private final SideEffectJournal journal;

	private final ApprovalStore approvalStore;

	private final Reversibility reversibility;

	private final RetryPolicy retryPolicy;

	SagacityToolCallback(ToolCallback delegate, SideEffectJournal journal, ApprovalStore approvalStore,
			Reversibility reversibility, RetryPolicy retryPolicy) {
		this.delegate = delegate;
		this.journal = journal;
		this.approvalStore = approvalStore;
		this.reversibility = reversibility;
		this.retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.NONE;
	}

	/** Backward-compatible — no approval gate, no retries. */
	SagacityToolCallback(ToolCallback delegate, SideEffectJournal journal) {
		this(delegate, journal, null, Reversibility.COMPENSATABLE, RetryPolicy.NONE);
	}

	/** Backward-compatible — approval gate, no retries. */
	SagacityToolCallback(ToolCallback delegate, SideEffectJournal journal, ApprovalStore approvalStore,
			Reversibility reversibility) {
		this(delegate, journal, approvalStore, reversibility, RetryPolicy.NONE);
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

		// Approval gate for IRREVERSIBLE tools — retries don't apply here
		if (this.reversibility == Reversibility.IRREVERSIBLE && this.approvalStore != null) {
			var intentEntry = this.journal.append(sagaId, toolName, Phase.AWAITING_APPROVAL, toolInput, "");
			String inputHash = HashChain.sha256(toolInput);
			this.approvalStore.save(new ApprovalRequest(sagaId, intentEntry.seq(), toolName, toolInput, inputHash));
			SagaScope.markAwaitingApproval(toolName, intentEntry.seq());
			return "[AWAITING_APPROVAL] Tool '" + toolName + "' requires human approval before execution.";
		}

		this.journal.append(sagaId, toolName, Phase.INTENT, toolInput, "");

		// Execute with retry policy
		int maxAttempts = retryPolicy.hasRetries() ? retryPolicy.maxAttempts() : 1;
		RuntimeException lastException = null;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			// Sleep before retry (never before the first attempt)
			if (attempt > 1) {
				long delayMs = retryPolicy.delayBeforeAttempt(attempt);
				if (delayMs > 0) {
					try {
						Thread.sleep(delayMs);
					}
					catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						break; // treat interrupt as non-retryable
					}
				}
			}

			try {
				String result = this.delegate.call(toolInput, toolContext);
				// Success — journal EXECUTED and return (retries are transparent)
				this.journal.append(sagaId, toolName, Phase.EXECUTED, toolInput, result != null ? result : "");
				return result;
			}
			catch (RuntimeException ex) {
				lastException = ex;

				boolean retryable = retryPolicy.hasRetries() && retryPolicy.isRetryable(ex);
				boolean attemptsRemaining = attempt < maxAttempts;

				if (retryable && attemptsRemaining) {
					// Transient failure — silently retry (no journal entry for this attempt)
					continue;
				}

				// Non-retryable, or retries exhausted — journal FAILED and compensate
				Throwable rootCause = ex.getCause() != null ? ex.getCause() : ex;
				String detail = rootCause.getMessage() != null ? rootCause.getMessage()
						: rootCause.getClass().getSimpleName();
				this.journal.append(sagaId, toolName, Phase.FAILED, toolInput, detail);
				SagaScope.markFailed(rootCause);
				throw ex;
			}
		}

		// Reached only if interrupted mid-retry — treat as failure
		Throwable rootCause = lastException != null
				? (lastException.getCause() != null ? lastException.getCause() : lastException)
				: new IllegalStateException("Retry loop exited without result");
		String detail = rootCause.getMessage() != null ? rootCause.getMessage()
				: rootCause.getClass().getSimpleName();
		this.journal.append(sagaId, toolName, Phase.FAILED, toolInput, detail);
		SagaScope.markFailed(rootCause);
		if (lastException != null) throw lastException;
		throw new IllegalStateException("Tool execution failed after retry interruption");
	}

}
